/*
 * Travis Scheduler
 * Copyright (C) 2015 SonarSource
 * sonarqube@googlegroups.com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02
 */
package org.sonar.travisscheduler;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.squareup.okhttp.HttpUrl;
import com.squareup.okhttp.MediaType;
import com.squareup.okhttp.OkHttpClient;
import com.squareup.okhttp.Request;
import com.squareup.okhttp.RequestBody;
import com.squareup.okhttp.Response;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class Main {

  public static void main(String[] args) throws Exception {
    String githubToken = System.getenv("GITHUB_TOKEN");
    if (githubToken == null) {
      throw new IllegalArgumentException("Mandatory environment variable GITHUB_TOKEN is missing!");
    }

    TravisClient client = new TravisClient("https://api.travis-ci.com", githubToken);
    scheduleBuilds(client, "SonarSource");

    client = new TravisClient("https://api.travis-ci.org", githubToken);
    scheduleBuilds(client, "SonarSource");
    //we do not schedule builds on SonarQubeCommunity anymore
    //scheduleBuilds(client, "SonarQubeCommunity");

    System.out.println("Done");
  }

  private static void scheduleBuilds(TravisClient client, String ownerName) throws Exception {
    double total = 0;
    double failures = 0;

    for (String slug : client.activeReposSlug(ownerName)) {
      total++;

      System.out.println("Launching the build of: " + slug);
      client.deleteMasterBranchCache(slug);
      try {
        client.startBuildOfMasterBranch(slug);
      } catch (IllegalStateException e) {
        failures++;
        System.out.println("  - FAILED! " + e.getMessage());
      }
      Thread.sleep(15000);
    }

    if (failures / total >= 0.3) {
      throw new IllegalStateException("Too many jobs relaunches failed for: " + ownerName);
    }
  }

  private static class TravisClient {

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private final JsonParser parser = new JsonParser();
    private final OkHttpClient client = new OkHttpClient();

    private final String endpoint;
    private final String githubToken;
    private String accessToken;

    public TravisClient(String endpoint, String githubToken) {
      this.endpoint = endpoint;
      this.githubToken = githubToken;

      this.client.setConnectTimeout(90, TimeUnit.SECONDS);
      this.client.setReadTimeout(90, TimeUnit.SECONDS);
      this.client.setWriteTimeout(90, TimeUnit.SECONDS);
    }

    public void ensureAuthenticated() {
      if (accessToken != null) {
        return;
      }

      JsonObject jsonGithubToken = new JsonObject();
      jsonGithubToken.addProperty("github_token", githubToken);
      RequestBody body = RequestBody.create(JSON, jsonGithubToken.toString());

      Request request = newRequestBuilder("/auth/github")
        .post(body)
        .build();
      String response = executeCall(request);
      accessToken = parser.parse(response).getAsJsonObject().get("access_token").getAsString();
    }

    public List<String> activeReposSlug(String ownerName) {
      ensureAuthenticated();
      Request request = newRequestBuilder(
        newHttpBuilder("/v3/owner/")
          .addPathSegment(ownerName)
          .addQueryParameter("include", "user.repositories,organization.repositories,repository.active")
          .build())
        .get()
        .build();
      String response = executeCall(request);

      List<String> result = new ArrayList<String>();
      JsonArray repos = parser.parse(response).getAsJsonObject().getAsJsonArray("repositories");
      for (int i = 0; i < repos.size(); i++) {
        JsonObject repo = repos.get(i).getAsJsonObject();
        if (repo.get("active").getAsBoolean()) {
          result.add(repo.get("slug").getAsString());
        }
      }
      return Collections.unmodifiableList(result);
    }

    public void deleteMasterBranchCache(String slug) {
      ensureAuthenticated();

      Request request = newRequestBuilder(
        newHttpBuilder("/repos/" + slug + "/caches")
          .addQueryParameter("branch", "master")
          .build())
        .delete()
        .build();
      executeCall(request);
    }

    public void startBuildOfMasterBranch(String slug) {
      ensureAuthenticated();

      JsonObject jsonRequestParams = new JsonObject();
      jsonRequestParams.addProperty("branch", "master");
      jsonRequestParams.addProperty("message", "Nightly build launched from the travis-scheduler job on AppVeyor");
      JsonObject jsonRequest = new JsonObject();
      jsonRequest.add("request", jsonRequestParams);
      RequestBody body = RequestBody.create(JSON, jsonRequest.toString());

      Request request = newRequestBuilder(newHttpBuilder("/repo/" + slug.replace("/", "%2F") + "/requests").build())
        .header("Travis-API-Version", "3")
        .post(body)
        .build();
      String response = executeCall(request, 202, 403);

      if (!"pending".equals(parser.parse(response).getAsJsonObject().get("@type").getAsString())) {
        throw new IllegalStateException("Unexpected response: " + response);
      }
    }

    private HttpUrl.Builder newHttpBuilder(String path) {
      return HttpUrl.parse(endpoint + path).newBuilder();
    }

    private Request.Builder newRequestBuilder(String path) {
      return newRequestBuilder(newHttpBuilder(path).build());
    }

    private Request.Builder newRequestBuilder(HttpUrl url) {
      Request.Builder request = new Request.Builder();
      request.url(url);
      request.addHeader("Accept", "application/json");
      request.addHeader("User-Agent", "SonarSource");
      if (accessToken != null) {
        request.addHeader("Authorization", "token " + accessToken);
      }
      return request;
    }

    private String executeCall(Request request) {
      return executeCall(request, 200);
    }

    private String executeCall(Request request, int... expectedCodes) {
      try {
        Response response = client.newCall(request).execute();

        boolean found = false;
        StringBuilder sb = new StringBuilder();
        for (int expectedCode : expectedCodes) {
          sb.append(expectedCode);
          sb.append(", ");
          if (response.code() == expectedCode) {
            found = true;
            break;
          }
        }

        if (!found) {
          String expected = sb.substring(0, sb.length() - 2).toString();
          throw new IllegalStateException("Expected HTTP response " + expected + ", actual was " + response.code() + ": " + response.body().string());
        }
        return response.body().string();
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    }

  }

}
