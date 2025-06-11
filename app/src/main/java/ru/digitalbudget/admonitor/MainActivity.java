package ru.digitalbudget.admonitor;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {
    public static final int REQUEST_MEDIA_PROJECTION = 100;
    private MediaProjectionManager projectionManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        projectionManager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        if (projectionManager == null) {
            Toast.makeText(this, "Ошибка: MediaProjectionManager == null", Toast.LENGTH_LONG).show();
            Log.e("MainActivity", "MediaProjectionManager == null");
            return;
        }

        requestScreenCapture();

//        if (!isAccessibilityServiceEnabled()) {
//            Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
//            startActivity(intent);
//            Toast.makeText(this, "Включите AdMonitor в настройках доступности", Toast.LENGTH_LONG).show();
//        } else {
//            Toast.makeText(this, "Сервис мониторинга активен!", Toast.LENGTH_SHORT).show();
//
//        }
    }

    private void requestScreenCapture() {
        if (projectionManager == null) {
            Toast.makeText(this, "Ошибка: MediaProjectionManager не доступен", Toast.LENGTH_SHORT).show();
            return;
        }
        startActivityForResult(
                projectionManager.createScreenCaptureIntent(),
                REQUEST_MEDIA_PROJECTION
        );
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQUEST_MEDIA_PROJECTION) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                // Разрешение получено! Передаем данные в сервис
                Intent serviceIntent = new Intent(this, AdMonitorService.class);
                serviceIntent.setAction("START_CAPTURE");
                serviceIntent.putExtra("media_projection_result_code", resultCode);
                serviceIntent.putExtra("media_projection_data", data);

                // Запускаем сервис (для Android 8+ используем startForegroundService)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent);
                } else {
                    startService(serviceIntent);
                }
            } else {
                Toast.makeText(this, "Запись экрана запрещена", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private boolean isAccessibilityServiceEnabled() {
        String serviceName = getPackageName() + "/" + AdMonitorService.class.getName();
        int accessibilityEnabled = Settings.Secure.getInt(
                getContentResolver(),
                Settings.Secure.ACCESSIBILITY_ENABLED,
                0
        );
        if (accessibilityEnabled == 1) {
            String enabledServices = Settings.Secure.getString(
                    getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            );
            return enabledServices != null && enabledServices.contains(serviceName);
        }
        return false;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
    }
}