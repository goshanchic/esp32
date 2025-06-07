package com.example.esp32;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.components.Legend;
import com.github.mikephil.charting.components.XAxis;
import com.github.mikephil.charting.components.YAxis;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.data.LineDataSet;
import com.github.mikephil.charting.interfaces.datasets.ILineDataSet;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
// Основная активность приложения для работы с BLE и отображения данных с датчика MPU6050
@SuppressLint("MissingPermission") // Разрешения проверяются динамически
public class MainActivity extends AppCompatActivity {

    private static final String TAG = "BLEApp";
    private static final String TARGET_DEVICE_NAME_ESP = "MPU6050_BLE";

    private static final UUID MPU_SERVICE_UUID = UUID.fromString("4fafc201-1fb5-459e-8fcc-c5c9c331914b");
    private static final UUID MPU_DATA_CHARACTERISTIC_UUID = UUID.fromString("beb5483e-36e1-4688-b7f5-ea07361b26a8");
    private static final UUID CCC_DESCRIPTOR_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private BluetoothAdapter bluetoothAdapter;
    private BluetoothLeScanner bluetoothLeScanner;
    private BluetoothGatt bluetoothGatt;
    private Handler handler;
    private boolean scanning = false; // флаг сканирования
    private boolean isSubscribed = false; // флаг подписки на данные

    // UI элементы
    private Button connectButton;
    private Button subscribeButton;
    private TextView statusTextView;
    private TextView yawTextView, pitchTextView, rollTextView; // для отображение углов
    private LineChart lineChart; // визуализация данных

    private static final int REQUEST_ENABLE_BT = 1;
    private static final int REQUEST_PERMISSIONS_CODE = 101;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Инициализация UI
        connectButton = findViewById(R.id.connectButton);
        subscribeButton = findViewById(R.id.subscribeButton);
        statusTextView = findViewById(R.id.statusTextView);
        yawTextView = findViewById(R.id.yawTextView);
        pitchTextView = findViewById(R.id.pitchTextView);
        rollTextView = findViewById(R.id.rollTextView);
        lineChart = findViewById(R.id.lineChart);

        handler = new Handler(Looper.getMainLooper());

        setupChart();
        // получение Bluetoth адаптерв
        final BluetoothManager bluetoothManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        if (bluetoothManager != null) {
            bluetoothAdapter = bluetoothManager.getAdapter();
        }
// проверка поддержки BLE
        if (bluetoothAdapter == null || !getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            showToast("BLE не поддерживается");
            finish();
            return;
        }
// Обработка кнопки подкл/отключ
        connectButton.setOnClickListener(v -> {
            if (bluetoothGatt != null && bluetoothGatt.getDevice() != null) {
                disconnectGatt();
            } else {
                if (checkAndRequestPermissions()) {
                    if (!bluetoothAdapter.isEnabled()) {
                        // запрос на вкл блютуз
                        Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
                        startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT);
                    } else {
                        startBleScan();
                    }
                }
            }
        });
        // обработчик кнопки подписка/отписка
        subscribeButton.setOnClickListener(v -> {
            if (isSubscribed) {
                unsubscribeFromMPUData();
            } else {
                subscribeToMPUData();
            }
        });
    }

    //region Настройка BLE
    // сканирование бле устройств
    private void startBleScan() {
        if (scanning) return;

        if (bluetoothLeScanner == null) {
            bluetoothLeScanner = bluetoothAdapter.getBluetoothLeScanner();
            if (bluetoothLeScanner == null) {
                showToast("Не удалось получить BluetoothLeScanner");
                return;
            }
        }
        updateStatus("Сканирование...");
        connectButton.setText("Остановить сканирование");
        resetUI();
        clearChart();

        scanning = true;
        // фильтр для поиска только нашего устройства
        ScanFilter filter = new ScanFilter.Builder().setDeviceName(TARGET_DEVICE_NAME_ESP).build();
        List<ScanFilter> filters = new ArrayList<>();
        filters.add(filter);
        ScanSettings settings = new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build();

        bluetoothLeScanner.startScan(filters, settings, leScanCallback);
        handler.postDelayed(this::stopBleScan, 15000); // 15 секунд на сканирование
    }
    // остановка сканирования

    private void stopBleScan() {
        if (scanning) {
            scanning = false;
            bluetoothLeScanner.stopScan(leScanCallback);
            updateStatus("Сканирование завершено");
            if(bluetoothGatt == null) connectButton.setText("Подключиться");
        }
    }
    // обработка результатов сканирования
    private final ScanCallback leScanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            super.onScanResult(callbackType, result);
            BluetoothDevice device = result.getDevice();
            if (device.getName() != null && device.getName().equalsIgnoreCase(TARGET_DEVICE_NAME_ESP)) {
                stopBleScan();
                connectToDevice(device);
            }
        }
    };
    // подкл к найденому устройству
    private void connectToDevice(BluetoothDevice device) {
        updateStatus("Подключение к " + device.getName() + "...");
        bluetoothGatt = device.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE);
    }
    // отключ от устройства
    private void disconnectGatt() {
        if (bluetoothGatt != null) {
            bluetoothGatt.disconnect();
        }
    }

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            String deviceName = gatt.getDevice().getName();
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.i(TAG, "Успешно подключено к " + deviceName);
                updateStatus("Подключено к: " + deviceName);
                runOnUiThread(() -> {
                    connectButton.setText("Отключиться");
                    subscribeButton.setEnabled(true);
                });
                gatt.requestMtu(517);
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.i(TAG, "Отключено от " + deviceName);
                updateStatus("Отключено");
                runOnUiThread(() -> {
                    resetUI();
                    clearChart();
                    connectButton.setText("Подключиться");
                    subscribeButton.setEnabled(false);
                    subscribeButton.setText("Старт");
                });
                if (bluetoothGatt != null) {
                    bluetoothGatt.close();
                    bluetoothGatt = null;
                }
                isSubscribed = false;
            }
        }
        // получение данных от устройства
        @Override
        public void onMtuChanged(BluetoothGatt gatt, int mtu, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.i(TAG, "MTU изменен на " + mtu);
                gatt.discoverServices();
            } else {
                Log.e(TAG, "Ошибка изменения MTU: " + status);
            }
        }
        // обработка подписки/отписки
        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.i(TAG, "Сервисы обнаружены.");
                runOnUiThread(() -> showToast("Сервисы обнаружены. Готово к подписке."));
            } else {
                Log.w(TAG, "Ошибка обнаружения сервисов: " + status);
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, byte[] value) {
            if (MPU_DATA_CHARACTERISTIC_UUID.equals(characteristic.getUuid())) {
                parseAndDisplayMPUData(value);
            }
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS && CCC_DESCRIPTOR_UUID.equals(descriptor.getUuid())) {
                byte[] value = descriptor.getValue();
                if (value != null && value.length > 0 && value[0] == BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE[0]) {
                    isSubscribed = true;
                    Log.i(TAG, "Подписка на MPU активна");
                    runOnUiThread(() -> {
                        showToast("Подписка активна");
                        subscribeButton.setText("Стоп");
                    });
                } else {
                    isSubscribed = false;
                    Log.i(TAG, "Подписка на MPU отключена");
                    runOnUiThread(() -> {
                        showToast("Подписка отключена");
                        subscribeButton.setText("Старт");
                    });
                }
            } else {
                Log.e(TAG, "Ошибка записи дескриптора: " + status);
            }
        }
    };
    // подписка для пакета
    private void subscribeToMPUData() {
        toggleSubscription(true);
    }
    // отписка от пакета
    private void unsubscribeFromMPUData() {
        toggleSubscription(false);
    }
    // вкл/выкл подписки
    private void toggleSubscription(boolean enable) {
        if (bluetoothGatt == null) return;
        BluetoothGattService service = bluetoothGatt.getService(MPU_SERVICE_UUID);
        if (service == null) {
            showToast("Сервис MPU не найден");
            return;
        }
        BluetoothGattCharacteristic characteristic = service.getCharacteristic(MPU_DATA_CHARACTERISTIC_UUID);
        if (characteristic == null) {
            showToast("Характеристика MPU не найдена");
            return;
        }
        // вкл/выкл нотификаций
        bluetoothGatt.setCharacteristicNotification(characteristic, enable);
        BluetoothGattDescriptor descriptor = characteristic.getDescriptor(CCC_DESCRIPTOR_UUID);
        if (descriptor != null) {
            descriptor.setValue(enable ? BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE : BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE);
            bluetoothGatt.writeDescriptor(descriptor);
        }
    }


    // Графики
    private void setupChart() {
        lineChart.getDescription().setEnabled(false);
        lineChart.setTouchEnabled(true);
        lineChart.setDragEnabled(true);
        lineChart.setScaleEnabled(true);
        lineChart.setDrawGridBackground(false);
        lineChart.setPinchZoom(true);
        lineChart.setBackgroundColor(Color.TRANSPARENT);

        LineData data = new LineData();
        data.setValueTextColor(Color.WHITE);
        lineChart.setData(data);
// Легенды настройка
        Legend l = lineChart.getLegend();
        l.setForm(Legend.LegendForm.LINE);
        l.setTextColor(Color.WHITE);
        // настройка осей
        XAxis xl = lineChart.getXAxis();
        xl.setTextColor(Color.WHITE);
        xl.setDrawGridLines(false);
        xl.setAvoidFirstLastClipping(true);
        xl.setEnabled(true);

        YAxis leftAxis = lineChart.getAxisLeft();
        leftAxis.setTextColor(Color.WHITE);
        leftAxis.setDrawGridLines(true);

        YAxis rightAxis = lineChart.getAxisRight();
        rightAxis.setEnabled(false);
    }
    // добавление точек на графике
    private void addChartEntry(float yaw, float pitch, float roll) {
        LineData data = lineChart.getData();
        // для углов
        if (data != null) {
            ILineDataSet setYaw = data.getDataSetByIndex(0);
            ILineDataSet setPitch = data.getDataSetByIndex(1);
            ILineDataSet setRoll = data.getDataSetByIndex(2);

            if (setYaw == null) {
                setYaw = createSet("Yaw", Color.parseColor("#FF6347"));
                data.addDataSet(setYaw);
            }
            if (setPitch == null) {
                setPitch = createSet("Pitch", Color.parseColor("#32CD32"));
                data.addDataSet(setPitch);
            }
            if (setRoll == null) {
                setRoll = createSet("Roll", Color.parseColor("#1E90FF"));
                data.addDataSet(setRoll);
            }
// добавление новых точек
            data.addEntry(new Entry(setYaw.getEntryCount(), yaw), 0);
            data.addEntry(new Entry(setPitch.getEntryCount(), pitch), 1);
            data.addEntry(new Entry(setRoll.getEntryCount(), roll), 2);
            // обновление графика
            data.notifyDataChanged();
            lineChart.notifyDataSetChanged();
            lineChart.setVisibleXRangeMaximum(150);
            lineChart.moveViewToX(data.getEntryCount());
        }
    }
// создание набора данныз с графика
    private LineDataSet createSet(String label, int color) {
        LineDataSet set = new LineDataSet(null, label);
        set.setAxisDependency(YAxis.AxisDependency.LEFT);
        set.setColor(color);
        set.setLineWidth(2f);
        set.setDrawValues(false);
        set.setDrawCircles(false);
        set.setMode(LineDataSet.Mode.CUBIC_BEZIER);
        return set;
    }
    // очистка графика
    private void clearChart() {
        lineChart.getData().clearValues();
        lineChart.invalidate();
    }
// парсер данных с платы и отображ 3float по 4 байта=12байта
    private void parseAndDisplayMPUData(byte[] value) {
        if (value == null || value.length < 12) { // 3 float по 4 байта = 12 байт
            return;
        }
// преобразование байтов в float
        ByteBuffer buffer = ByteBuffer.wrap(value).order(ByteOrder.LITTLE_ENDIAN);
        final float yaw = buffer.getFloat();
        final float pitch = buffer.getFloat();
        final float roll = buffer.getFloat();
        // Остальные данные игнорируем, если они есть
// обновление и получения нужных данных
        runOnUiThread(() -> {
            yawTextView.setText(String.format(java.util.Locale.US, "Yaw: %.1f", yaw));
            pitchTextView.setText(String.format(java.util.Locale.US, "Pitch: %.1f", pitch));
            rollTextView.setText(String.format(java.util.Locale.US, "Roll: %.1f", roll));
            addChartEntry(yaw, pitch, roll);
        });
    }

    private void resetUI() {
        yawTextView.setText("Yaw: 0.0");
        pitchTextView.setText("Pitch: 0.0");
        rollTextView.setText("Roll: 0.0");
    }

    private void showToast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }
    // обновление статуса
    private void updateStatus(final String status) {
        runOnUiThread(() -> statusTextView.setText("Статус: " + status));
        Log.d(TAG, "Статус: " + status);
    }


    // проверка и запрос разрешений
    private boolean checkAndRequestPermissions() {
        List<String> permissionsNeeded = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(Manifest.permission.BLUETOOTH_SCAN);
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(Manifest.permission.BLUETOOTH_CONNECT);
            }
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            permissionsNeeded.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }

        if (!permissionsNeeded.isEmpty()) {
            ActivityCompat.requestPermissions(this, permissionsNeeded.toArray(new String[0]), REQUEST_PERMISSIONS_CODE);
            return false;
        }
        return true;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_PERMISSIONS_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                showToast("Разрешения получены. Попробуйте снова.");
            } else {
                showToast("Без разрешений приложение не сможет работать.");
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_ENABLE_BT && resultCode == RESULT_OK) {
            showToast("Bluetooth включен");
            startBleScan();
        } else if (requestCode == REQUEST_ENABLE_BT) {
            showToast("Bluetooth не был включен");
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        disconnectGatt();
        handler.removeCallbacksAndMessages(null);
    }

}