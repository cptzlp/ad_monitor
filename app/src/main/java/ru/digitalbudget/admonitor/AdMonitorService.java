package ru.digitalbudget.admonitor;

import android.accessibilityservice.AccessibilityService;
import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.provider.MediaStore;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import androidx.annotation.RequiresApi;
import androidx.core.app.NotificationCompat;


import java.nio.ByteBuffer;

public class AdMonitorService extends AccessibilityService {

    private static final String TAG = "AdMonitorService";
    private MediaProjection mediaProjection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private boolean isCaptureActive = false;

    private Bitmap latestBitmap = null;


    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
                "channel_id",
                "AdMonitor Channel",
                NotificationManager.IMPORTANCE_LOW
        );

        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.createNotificationChannel(channel);
        }
    }

    private Notification createNotification() {
        return new NotificationCompat.Builder(this, "admonitor_channel")
                .setContentTitle("AdMonitor Service")
                .setContentText("Мониторинг рекламы активен")
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    @RequiresApi(api = Build.VERSION_CODES.Q)
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Сначала создаём уведомительный канал (Android 8+)
        createNotificationChannel();

        // Затем сразу делаем сервис foreground
        Notification notification = new Notification.Builder(this, "channel_id")
                .setContentTitle("AdMonitor")
                .setContentText("Сервис работает...")
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .build();

        // ✅ ВАЖНО: Вызываем startForeground ДО любой работы с MediaProjection
        startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);

        // Теперь можно работать с данными
        if (intent != null && intent.hasExtra("media_projection_result_code")) {
            int resultCode = intent.getIntExtra("media_projection_result_code", Activity.RESULT_CANCELED);
            Intent data = intent.getParcelableExtra("media_projection_data");

            if (resultCode == Activity.RESULT_OK && data != null) {
                initializeMediaProjection(resultCode, data); // Инициализируем MediaProjection
                startScreenCapture();
            } else {
                Log.e("Service", "Ошибка: resultCode=" + resultCode + ", data=" + data);
                stopSelf();
            }
        } else {
            Log.e("Service", "Intent не содержит media_projection_result_code");
            stopSelf();
        }

        return START_STICKY;
    }

    private void initializeMediaProjection(int resultCode, Intent data) {
        MediaProjectionManager manager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        if (manager != null) {
            mediaProjection = manager.getMediaProjection(resultCode, data);

            if (mediaProjection != null) {
                Log.d(TAG, "MediaProjection успешно инициализирован");

                mediaProjection.registerCallback(new MediaProjection.Callback() {
                    @Override
                    public void onStop() {
                        super.onStop();
                        Log.e("MediaProjection", "MediaProjection остановлен пользователем");
                        cleanupResources();
                    }
                }, null);
            }


        }
    }

    private void startScreenCapture() {
        if (mediaProjection == null || isCaptureActive) {
            return;
        }

        try {
            int width = getResources().getDisplayMetrics().widthPixels;
            int height = getResources().getDisplayMetrics().heightPixels;
            int densityDpi = getResources().getDisplayMetrics().densityDpi;

            imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2);
            imageReader.setOnImageAvailableListener(reader -> {
                try (Image image = reader.acquireNextImage()) {
                    processImage(image);
                } catch (Exception e) {
                    Log.e(TAG, "Ошибка при получении нового кадра", e);
                }
            }, null);

            virtualDisplay = mediaProjection.createVirtualDisplay(
                    "AdCaptureDisplay",
                    width,
                    height,
                    densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    imageReader.getSurface(),
                    null,
                    null
            );

            isCaptureActive = true;
            Log.d(TAG, "Захват экрана запущен");
        } catch (Exception e) {
            Log.e(TAG, "Ошибка запуска захвата", e);
            cleanupResources();
        }
    }


    private void processImage(Image image) {
        if (image == null) return;

        try {
            Image.Plane[] planes = image.getPlanes();
            ByteBuffer buffer = planes[0].getBuffer();

            int width = image.getWidth();
            int height = image.getHeight();

            if (latestBitmap == null || latestBitmap.getWidth() != width || latestBitmap.getHeight() != height) {
                latestBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            }

            latestBitmap.copyPixelsFromBuffer(buffer);
            //Log.d(TAG, "Кадр экрана обновлён");

        } catch (Exception e) {
            Log.e(TAG, "Ошибка обработки кадра", e);
        } finally {
            image.close(); // Закрываем только Image
        }
    }

    private void saveToGallery(Bitmap bitmap) {
        try {
            String saved = MediaStore.Images.Media.insertImage(
                    getContentResolver(),
                    bitmap,
                    "ad_banner_" + System.currentTimeMillis(),
                    "Detected advertisement banner"
            );

            if (saved != null) {
                Log.i(TAG, "Скриншот сохранен в галерею: " + saved);
            } else {
                Log.e(TAG, "Ошибка сохранения в галерею");
            }
        } catch (Exception e) {
            Log.e(TAG, "Ошибка сохранения изображения", e);
        }
    }

    private void cleanupResources() {
        if (virtualDisplay != null) {
            virtualDisplay.release();
            virtualDisplay = null;
        }
        if (imageReader != null) {
            imageReader.close();
            imageReader = null;
        }
        isCaptureActive = false;
        latestBitmap = null;
    }

    public void takeScreenshotOfAdBanner(Rect area) {

        if (latestBitmap == null) {
            Log.e(TAG, "Нет доступного кадра для обрезки");
            return;
        }

        if (!isValidRect(area, latestBitmap)) {
            Log.e(TAG, "Неверные координаты для обрезки: " + area.toShortString());
            return;
        }

        Bitmap cropped = Bitmap.createBitmap(latestBitmap, area.left, area.top, area.width(), area.height());
        saveToGallery(cropped);
    }

    private boolean isValidRect(Rect area, Bitmap bitmap) {
        return !area.isEmpty() &&
                area.left >= 0 &&
                area.top >= 0 &&
                area.right < bitmap.getWidth() &&
                area.bottom < bitmap.getHeight();
    }

    private boolean isSystemApp(AccessibilityNodeInfo node) {
        String packageName = node.getPackageName().toString();
        return packageName.startsWith("com.android") || packageName.startsWith("com.google.android");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        int eventType = event.getEventType();
        if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            AccessibilityNodeInfo root = event.getSource();
            if (root != null && !isSystemApp(root)) {
                checkForAdBanner(root);
                root.recycle();
            }
        }
    }

    private void checkForAdBanner(AccessibilityNodeInfo node) {
        if (node == null) return;

        try {
            //package name: com.wildberries  РАЗМЕР баннера: +-1000х500
            if (node.getPackageName().toString().contains("com.wild")) {
                Rect rect = new Rect();
                node.getBoundsInScreen(rect);
                if (isAdBannerNode(node) && (rect.right - rect.left) > 700 && (rect.bottom - rect.top) > 300) {
                    Log.i(TAG, "Обнаружен рекламный баннер: " + rect.toShortString());
                    takeScreenshotOfAdBanner(rect);
                }
            }

            //package name: ru.zen.android  РАЗМЕР баннера: +-1100х1100
            //text reklami na child 6
            //ЯНДЕКС DZEN: получаем элемент реклама, берем его родителя, потом берем 3 ребенка и его текст
            if (node.getPackageName().toString().contains("ru.zen")) {
                if (node.getText() != null &&
                        (node.getText().toString().contains("Ad") || node.getText().toString().contains("Реклама"))) {
                    Log.i("Рекламодатель!", "Рекламодатель: " + node.getParent().getChild(2).getText().toString());
                    Log.i("Текст рекламы", "Text: " + node.getParent().getChild(6).getText().toString());
                    Rect rect = new Rect();
                    node.getParent().getBoundsInScreen(rect);
                    Log.i("Баннер", "Обнаружен рекламный баннер: " + rect.toShortString());
                    Log.i("Баннер", "Сделан скриншот баннера на ГЛАВНЫЙ ЭКРАН");
                    Log.i("Координаты баннера", rect.toShortString());
                    takeScreenshotOfAdBanner(rect);
                }
            }

            // package name: ru.auto.ara  РАЗМЕР баннера: 500х600
            // рекламодатель = 4
            // text = 5
            // "Подробнее" = 6
            // AUTO RU: особенное имя класса, важно чтобы узел имел 7 детей, имя рекламодателя под 4 индексом
            if (node.getPackageName().toString().contains("ru.auto")) {
                Log.i("Node", node.toString());
                if (node.getText() != null && node.getText().toString().contains("Реклама")) {
                    AccessibilityNodeInfo parentNode = node.getParent();
                    if (parentNode.getChildCount() >= 7) {
                        Log.i("Рекламодатель", "Рекламодатель: " + parentNode.getChild(4).getText().toString());
                        Log.i("Текст рекламы", "Текст рекл.: " + parentNode.getChild(5).getText().toString());
                    }
                }
            }


            // Рекурсивно проверяем дочерние узлы
            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) {
                    checkForAdBanner(child);
                    child.recycle();
                }
            }
        } catch (
                Exception e) {
            Log.e(TAG, "Ошибка проверки баннера", e);
        }
    }


    private boolean isAdBannerNode(AccessibilityNodeInfo node) {
        if (node.getContentDescription() == null) return false;

        String description = node.getContentDescription().toString();
        return description.startsWith("Рекл");
    }

    @Override
    public void onInterrupt() {
        cleanupResources();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (mediaProjection != null) {
            mediaProjection.stop();
            mediaProjection = null;
        }
        cleanupResources();
        Log.i(TAG, "Сервис остановлен");
    }
}