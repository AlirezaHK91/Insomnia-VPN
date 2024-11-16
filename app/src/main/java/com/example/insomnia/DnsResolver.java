package com.example.insomnia;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.io.IOException;

public class DnsResolver {
    private OkHttpClient client;

    public DnsResolver() {
        client = new OkHttpClient();
    }

    public void resolveDomain(String domain, final DnsCallback callback) {
        String url = "https://cloudflare-dns.com/dns-query?name=" + domain + "&type=A";

        Request request = new Request.Builder()
                .url(url)
                .addHeader("Accept", "application/dns-json")
                .build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                e.printStackTrace();
                callback.onFailure("Failed to resolve DNS");
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (response.isSuccessful()) {
                    String jsonResponse = response.body().string();
                    callback.onSuccess(jsonResponse);
                } else {
                    callback.onFailure("Error: " + response.code());
                }
            }
        });
    }

    public interface DnsCallback {
        void onSuccess(String response);

        void onFailure(String error);
    }
}
