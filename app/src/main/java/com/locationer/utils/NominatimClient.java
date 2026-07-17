package com.locationer.utils;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * A small, rate-limited client for the public OpenStreetMap Nominatim service.
 * Requests are serialized to comply with the public service's one request/second limit.
 */
public final class NominatimClient {
    private static final String BASE_URL = "https://nominatim.openstreetmap.org";
    private static final String USER_AGENT = "Locationer/1.12.3 (https://github.com/zcshou/gogogo)";
    private static final long MIN_REQUEST_INTERVAL_MS = 1_100L;
    private static final NominatimClient INSTANCE = new NominatimClient();

    private final OkHttpClient httpClient = new OkHttpClient();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private long lastRequestAt;

    private NominatimClient() {
    }

    public static NominatimClient getInstance() {
        return INSTANCE;
    }

    public void search(String query, Callback<List<Place>> callback) {
        executor.execute(() -> {
            try {
                HttpUrl url = requireBaseUrl("search").newBuilder()
                        .addQueryParameter("format", "jsonv2")
                        .addQueryParameter("limit", "10")
                        .addQueryParameter("q", query)
                        .build();
                JSONArray array = new JSONArray(execute(url));
                List<Place> places = new ArrayList<>();
                for (int i = 0; i < array.length(); i++) {
                    JSONObject item = array.getJSONObject(i);
                    places.add(new Place(
                            item.optString("name", item.optString("display_name", "")),
                            item.optString("display_name", ""),
                            item.getDouble("lat"),
                            item.getDouble("lon")));
                }
                callback.onSuccess(places);
            } catch (Exception e) {
                callback.onFailure(e);
            }
        });
    }

    private HttpUrl requireBaseUrl(String path) {
        HttpUrl url = HttpUrl.parse(BASE_URL + "/" + path);
        if (url == null) {
            throw new IllegalStateException("Invalid Nominatim URL");
        }
        return url;
    }

    private String execute(HttpUrl url) throws Exception {
        enforceRateLimit();
        Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.5")
                .get()
                .build();
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Nominatim HTTP " + response.code());
            }
            ResponseBody body = response.body();
            if (body == null) {
                throw new IOException("Nominatim returned an empty body");
            }
            return body.string();
        }
    }

    private void enforceRateLimit() throws InterruptedException {
        long wait = MIN_REQUEST_INTERVAL_MS - (System.currentTimeMillis() - lastRequestAt);
        if (wait > 0) {
            Thread.sleep(wait);
        }
        lastRequestAt = System.currentTimeMillis();
    }

    public interface Callback<T> {
        void onSuccess(T result);

        void onFailure(Exception error);
    }

    public static final class Place {
        public final String name;
        public final String displayName;
        public final double latitude;
        public final double longitude;

        Place(String name, String displayName, double latitude, double longitude) {
            this.name = name;
            this.displayName = displayName;
            this.latitude = latitude;
            this.longitude = longitude;
        }
    }
}
