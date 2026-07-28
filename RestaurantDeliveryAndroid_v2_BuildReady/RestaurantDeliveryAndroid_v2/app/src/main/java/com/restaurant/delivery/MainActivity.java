package com.restaurant.delivery;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

public class MainActivity extends Activity {
    private static final int LOCATION_REQUEST = 1001;
    private WebView webView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        webView = new WebView(this);
        setContentView(webView);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);

        webView.addJavascriptInterface(new AndroidBridge(), "Android");
        webView.setWebChromeClient(new WebChromeClient());
        webView.setWebViewClient(new WebViewClient() {
            private boolean openExternal(String url) {
                if (url.startsWith("tel:") || url.startsWith("geo:") ||
                        url.startsWith("https://www.google.com/maps") ||
                        url.startsWith("https://wa.me/")) {
                    try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); }
                    catch (Exception e) { Toast.makeText(MainActivity.this, "تعذر فتح التطبيق المطلوب", Toast.LENGTH_SHORT).show(); }
                    return true;
                }
                return false;
            }
            @Override public boolean shouldOverrideUrlLoading(WebView view, String url) { return openExternal(url); }
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) { return openExternal(request.getUrl().toString()); }
        });

        webView.loadUrl("file:///android_asset/index.html");
    }

    private void requestNativeLocation() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
                checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, LOCATION_REQUEST);
            return;
        }
        LocationManager lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        if (!lm.isProviderEnabled(LocationManager.GPS_PROVIDER) && !lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
            Toast.makeText(this, "فعّل خدمة الموقع ثم أعد المحاولة", Toast.LENGTH_LONG).show();
            startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS));
            return;
        }
        Location best = null;
        try {
            Location gps = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            Location net = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
            best = gps != null ? gps : net;
        } catch (SecurityException ignored) {}
        if (best != null) {
            sendLocation(best);
            return;
        }
        LocationListener listener = new LocationListener() {
            @Override public void onLocationChanged(Location location) {
                sendLocation(location);
                try { lm.removeUpdates(this); } catch (SecurityException ignored) {}
            }
        };
        try {
            String provider = lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ? LocationManager.GPS_PROVIDER : LocationManager.NETWORK_PROVIDER;
            lm.requestSingleUpdate(provider, listener, null);
            Toast.makeText(this, "جارٍ تحديد الموقع…", Toast.LENGTH_SHORT).show();
        } catch (SecurityException e) {
            Toast.makeText(this, "لم يتم منح إذن الموقع", Toast.LENGTH_SHORT).show();
        }
    }

    private void sendLocation(Location location) {
        final String js = "window.receiveNativeLocation(" + location.getLatitude() + "," + location.getLongitude() + ");";
        runOnUiThread(() -> webView.evaluateJavascript(js, null));
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == LOCATION_REQUEST && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            requestNativeLocation();
        } else if (requestCode == LOCATION_REQUEST) {
            Toast.makeText(this, "يلزم السماح بالموقع لحفظ عنوان العميل", Toast.LENGTH_LONG).show();
        }
    }

    public class AndroidBridge {
        @JavascriptInterface public void getCurrentLocation() { runOnUiThread(MainActivity.this::requestNativeLocation); }
        @JavascriptInterface public void openMap(double lat, double lng, boolean directions) {
            String url = directions ? "https://www.google.com/maps/dir/?api=1&destination=" + lat + "," + lng
                    : "https://www.google.com/maps/search/?api=1&query=" + lat + "," + lng;
            runOnUiThread(() -> { try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); } catch (Exception ignored) {} });
        }
        @JavascriptInterface public void callPhone(String phone) {
            runOnUiThread(() -> { try { startActivity(new Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + phone))); } catch (Exception ignored) {} });
        }
        @JavascriptInterface public void whatsapp(String phone, String text) {
            String clean = phone.replaceAll("\\D", "");
            if (clean.startsWith("0")) clean = "966" + clean.substring(1);
            final String url = "https://wa.me/" + clean + "?text=" + URLEncoder.encode(text, StandardCharsets.UTF_8);
            runOnUiThread(() -> { try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); } catch (Exception ignored) {} });
        }
        @JavascriptInterface public void shareText(String text) {
            runOnUiThread(() -> {
                Intent send = new Intent(Intent.ACTION_SEND);
                send.setType("text/plain"); send.putExtra(Intent.EXTRA_TEXT, text);
                startActivity(Intent.createChooser(send, "مشاركة الطلب"));
            });
        }
    }

    @Override public void onBackPressed() {
        if (webView != null && webView.canGoBack()) webView.goBack(); else super.onBackPressed();
    }
}
