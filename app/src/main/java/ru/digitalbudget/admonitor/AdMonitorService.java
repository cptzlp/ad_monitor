package ru.digitalbudget.admonitor;

import android.accessibilityservice.AccessibilityService;
import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
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
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Toast;

import androidx.annotation.RequiresApi;
import androidx.core.app.NotificationCompat;

import java.nio.ByteBuffer;
import java.util.HashSet;
import java.util.Set;

public class AdMonitorService extends AccessibilityService {

    private final Set<AccessibilityNodeInfo> banners = new HashSet<>();
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

//    private void processImage(Image image) {
//        if (image == null) return;
//
//        try {
//            Image.Plane[] planes = image.getPlanes();
//            ByteBuffer buffer = planes[0].getBuffer();
//
//            Bitmap bitmap = Bitmap.createBitmap(
//                    image.getWidth(),
//                    image.getHeight(),
//                    Bitmap.Config.ARGB_8888
//            );
//            bitmap.copyPixelsFromBuffer(buffer);
//
//            Rect area = new Rect(42, 300, 1038, 805);
//            if (area.left >= 0 && area.top >= 0 &&
//                    area.right <= bitmap.getWidth() &&
//                    area.bottom <= bitmap.getHeight()) {
//
//                Bitmap cropped = Bitmap.createBitmap(
//                        bitmap,
//                        area.left,
//                        area.top,
//                        area.width(),
//                        area.height()
//                );
//
//                saveToGallery(cropped);
//            }
//        } catch (Exception e) {
//            Log.e(TAG, "Ошибка обработки изображения", e);
//        }
//    }

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
                area.right <= bitmap.getWidth() &&
                area.bottom <= bitmap.getHeight();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root != null && !root.getPackageName().toString().startsWith("com.android")) {
                Log.i("Current App", "Текущее приложение: " + root.getPackageName().toString());
                checkForAdBanner(root);
                root.recycle();
            }
        }
        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root != null && !root.getPackageName().toString().startsWith("com.android")) {
                checkForAdBanner(root);
                root.recycle();
            }
        }
    }

    private void checkForAdBanner(AccessibilityNodeInfo node) {
        if (node == null) return;

        try {
            Rect rect = new Rect();
            node.getBoundsInScreen(rect);
            // Проверяем текущий узел
//            && !banners.contains(node)
            //rect.left >= 0 && rect.top <= 300 && rect.right <= 1080 && rect.bottom <= 800
            //
            if (isAdBannerNode(node)  &&  (rect.right - rect.left) > 700 && (rect.bottom - rect.top) > 300) {
                Log.i(TAG, "Обнаружен рекламный баннер: " + rect.toShortString());
                takeScreenshotOfAdBanner(new Rect(42, 300, 1038, 805));
                //startScreenCapture();
                banners.add(node);

            }

            // Рекурсивно проверяем дочерние узлы
            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) {
                    checkForAdBanner(child);
                    child.recycle();
                }
            }
        } catch (Exception e) {
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