package com.sedmelluq.discord.lavaplayer.source.youtube;

import com.sedmelluq.discord.lavaplayer.tools.DataFormatTools;
import com.sedmelluq.discord.lavaplayer.tools.ExceptionTools;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.tools.JsonBrowser;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpClientTools;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterface;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.util.EntityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

import static com.sedmelluq.discord.lavaplayer.source.youtube.YoutubeConstants.PLAYER_PAYLOAD;
import static com.sedmelluq.discord.lavaplayer.source.youtube.YoutubeConstants.PLAYER_URL;
import static com.sedmelluq.discord.lavaplayer.source.youtube.YoutubeConstants.VERIFY_AGE_PAYLOAD;
import static com.sedmelluq.discord.lavaplayer.source.youtube.YoutubeConstants.VERIFY_AGE_URL;
import static com.sedmelluq.discord.lavaplayer.source.youtube.YoutubeConstants.WATCH_URL_PREFIX;
import static com.sedmelluq.discord.lavaplayer.source.youtube.YoutubeHttpContextFilter.PBJ_PARAMETER;
import static com.sedmelluq.discord.lavaplayer.tools.ExceptionTools.throwWithDebugInfo;
import static com.sedmelluq.discord.lavaplayer.tools.FriendlyException.Severity.COMMON;
import static com.sedmelluq.discord.lavaplayer.tools.FriendlyException.Severity.SUSPICIOUS;
import static java.nio.charset.StandardCharsets.UTF_8;

public class DefaultYoutubeTrackDetailsLoader implements YoutubeTrackDetailsLoader {
  private static final Logger log = LoggerFactory.getLogger(DefaultYoutubeTrackDetailsLoader.class);

  private volatile CachedPlayerScript cachedPlayerScript = null;

  @Override
  public YoutubeTrackDetails loadDetails(HttpInterface httpInterface, String videoId, boolean requireFormats, YoutubeAudioSourceManager sourceManager) {
    try {
      return load(httpInterface, videoId, requireFormats, sourceManager);
    } catch (IOException e) {
      throw ExceptionTools.toRuntimeException(e);
    }
  }

  private YoutubeTrackDetails load(
      HttpInterface httpInterface,
      String videoId,
      boolean requireFormats,
      YoutubeAudioSourceManager sourceManager
  ) throws IOException {
    JsonBrowser mainInfo = loadTrackInfoFromInnertube(httpInterface, videoId, sourceManager);

    try {
      YoutubeTrackJsonData initialData = loadBaseResponse(mainInfo, httpInterface, videoId);

      if (initialData == null) {
        return null;
      }

      YoutubeTrackJsonData finalData = augmentWithPlayerScript(initialData, httpInterface, requireFormats);
      return new DefaultYoutubeTrackDetails(videoId, finalData);
    } catch (FriendlyException e) {
      throw e;
    } catch (Exception e) {
      throw throwWithDebugInfo(log, e, "Error when extracting data", "mainJson", mainInfo.format());
    }
  }

  protected YoutubeTrackJsonData loadBaseResponse(
      JsonBrowser mainInfo,
      HttpInterface httpInterface,
      String videoId
  ) throws IOException {
    YoutubeTrackJsonData data = YoutubeTrackJsonData.fromMainResult(mainInfo);
    InfoStatus status = checkPlayabilityStatus(data.playerResponse);

    if (status == InfoStatus.DOES_NOT_EXIST) {
      return null;
    }

    if (status == InfoStatus.CONTENT_CHECK_REQUIRED) {
      JsonBrowser trackInfo = loadTrackInfoWithContentVerify(httpInterface, videoId);
      return YoutubeTrackJsonData.fromMainResult(trackInfo);
    }

    return data;
  }

  protected InfoStatus checkPlayabilityStatus(JsonBrowser playerResponse) {
    JsonBrowser statusBlock = playerResponse.get("playabilityStatus");

    if (statusBlock.isNull()) {
      throw new RuntimeException("No playability status block.");
    }

    String status = statusBlock.get("status").text();

    if (status == null) {
      throw new RuntimeException("No playability status field.");
    } else if ("OK".equals(status)) {
      return InfoStatus.INFO_PRESENT;
    } else if ("ERROR".equals(status)) {
      String reason = statusBlock.get("reason").text();

      if ("Video unavailable".equals(reason)) {
        return InfoStatus.DOES_NOT_EXIST;
      } else {
        throw new FriendlyException(reason, COMMON, null);
      }
    } else if ("UNPLAYABLE".equals(status)) {
      String unplayableReason = getUnplayableReason(statusBlock);
      throw new FriendlyException(unplayableReason, COMMON, null);
    } else if ("LOGIN_REQUIRED".equals(status)) {
      String errorReason = statusBlock.get("errorScreen")
          .get("playerErrorMessageRenderer")
          .get("reason")
          .get("simpleText")
          .text();

      if ("Private video".equals(errorReason)) {
        throw new FriendlyException("This is a private video.", COMMON, null);
      }

      return InfoStatus.REQUIRES_LOGIN;
    } else if ("CONTENT_CHECK_REQUIRED".equals(status)) {
      return InfoStatus.CONTENT_CHECK_REQUIRED;
    } else {
      throw new FriendlyException("This video cannot be viewed anonymously.", COMMON, null);
    }
  }

  protected enum InfoStatus {
    INFO_PRESENT,
    REQUIRES_LOGIN,
    DOES_NOT_EXIST,
    CONTENT_CHECK_REQUIRED
  }

  protected String getUnplayableReason(JsonBrowser statusBlock) {
    JsonBrowser playerErrorMessage = statusBlock.get("errorScreen").get("playerErrorMessageRenderer");
    String unplayableReason = statusBlock.get("reason").text();

    if (!playerErrorMessage.get("subreason").isNull()) {
      JsonBrowser subreason = playerErrorMessage.get("subreason");

      if (!subreason.get("simpleText").isNull()) {
        unplayableReason = subreason.get("simpleText").text();
      } else if (!subreason.get("runs").isNull() && subreason.get("runs").isList()) {
        StringBuilder reasonBuilder = new StringBuilder();
        subreason.get("runs").values().forEach(
            item -> reasonBuilder.append(item.get("text").text()).append('\n')
        );
        unplayableReason = reasonBuilder.toString();
      }
    }

    return unplayableReason;
  }

  protected JsonBrowser loadTrackInfoFromInnertube(HttpInterface httpInterface, String videoId, YoutubeAudioSourceManager sourceManager) throws IOException {
    if (cachedPlayerScript == null) fetchScript(httpInterface);

    YoutubeSignatureCipher playerScriptTimestamp = sourceManager.getSignatureResolver().getCipherKeyAndTimestampFromScript(httpInterface,
            cachedPlayerScript.playerScriptUrl);
    HttpPost post = new HttpPost(PLAYER_URL);
    StringEntity payload = new StringEntity(String.format(PLAYER_PAYLOAD, videoId, playerScriptTimestamp.scriptTimestamp), "UTF-8");
    post.setEntity(payload);

    try (CloseableHttpResponse response = httpInterface.execute(post)) {
      return processResponse(response);
    }
  }

  protected JsonBrowser loadTrackInfoFromMainPage(HttpInterface httpInterface, String videoId) throws IOException {
    String url = WATCH_URL_PREFIX + videoId + PBJ_PARAMETER + "&hl=en";

    try (CloseableHttpResponse response = httpInterface.execute(new HttpGet(url))) {
      return processResponse(response);
    }
  }

  protected JsonBrowser loadTrackInfoWithContentVerify(HttpInterface httpInterface, String videoId) throws IOException {
    HttpPost post = new HttpPost(VERIFY_AGE_URL);
    StringEntity payload = new StringEntity(String.format(VERIFY_AGE_PAYLOAD, "/watch?v=" + videoId), "UTF-8");
    post.setEntity(payload);
    try (CloseableHttpResponse response = httpInterface.execute(post)) {
      HttpClientTools.assertSuccessWithContent(response, "content verify response");

      String json = EntityUtils.toString(response.getEntity(), UTF_8);
      String fetchedContentVerifiedLink = JsonBrowser.parse(json)
          .get("actions")
          .index(0)
          .get("navigateAction")
          .get("endpoint")
          .get("urlEndpoint")
          .get("url")
          .text();
      if (fetchedContentVerifiedLink != null) {
        return loadTrackInfoFromMainPage(httpInterface, fetchedContentVerifiedLink.substring(9));
      }

      log.error("Did not receive requested content verified link on track {} response: {}", videoId, json);
    }

    throw new FriendlyException("Track requires content verification.", SUSPICIOUS,
            new IllegalStateException("Expected response is not present."));
  }

  protected JsonBrowser processResponse(CloseableHttpResponse response) throws IOException {
    HttpClientTools.assertSuccessWithContent(response, "video page response");

    String responseText = EntityUtils.toString(response.getEntity(), UTF_8);

    try {
      return JsonBrowser.parse(responseText);
    } catch (FriendlyException e) {
      throw e;
    } catch (Exception e) {
      throw new FriendlyException("Received unexpected response from YouTube.", SUSPICIOUS,
              new RuntimeException("Failed to parse: " + responseText, e));
    }
  }

  protected YoutubeTrackJsonData augmentWithPlayerScript(
          YoutubeTrackJsonData data,
          HttpInterface httpInterface,
          boolean requireFormats
  ) throws IOException {
    long now = System.currentTimeMillis();

    if (data.playerScriptUrl != null) {
      cachedPlayerScript = new CachedPlayerScript(data.playerScriptUrl, now);
      return data;
    } else if (!requireFormats) {
      return data;
    }

    CachedPlayerScript cached = cachedPlayerScript;

    if (cached != null && cached.timestamp + 600000L >= now) {
      return data.withPlayerScriptUrl(cached.playerScriptUrl);
    }

    return data.withPlayerScriptUrl(fetchScript(httpInterface));
  }

  private String fetchScript(HttpInterface httpInterface) throws IOException {
    long now = System.currentTimeMillis();

    try (CloseableHttpResponse response = httpInterface.execute(new HttpGet("https://www.youtube.com/embed"))) {
      HttpClientTools.assertSuccessWithContent(response, "youtube embed video id");
      String responseText = EntityUtils.toString(response.getEntity());
      String encodedUrl = DataFormatTools.extractBetween(responseText, "\"jsUrl\":\"", "\"");

      if (encodedUrl == null) {
        throw throwWithDebugInfo(log, null, "no jsUrl found", "html", responseText);
      }

      String fetchedPlayerScript = JsonBrowser.parse("{\"url\":\"" + encodedUrl + "\"}").get("url").text();
      cachedPlayerScript = new CachedPlayerScript(fetchedPlayerScript, now);

      return fetchedPlayerScript;
    }
  }

  protected static class CachedPlayerScript {
    public final String playerScriptUrl;
    public final long timestamp;

    public CachedPlayerScript(String playerScriptUrl, long timestamp) {
      this.playerScriptUrl = playerScriptUrl;
      this.timestamp = timestamp;
    }
  }
}
