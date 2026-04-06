package com.example.pixelfluidsimulation;

import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity implements SensorEventListener {

    private LinearLayout panelControls;
    private Button btnToggleMenu;
    private TextView btnClose;
    private SensorManager sensorManager;
    private Sensor accelerometer;
    private PixelFluidView fluidView;
    private CheckBox checkBoxParticle;
    private CheckBox checkBoxPixel;
    private CheckBox checkBoxFPS;
    private CheckBox checkBoxGrid;
    private SeekBar seekBarStiffness;
    private SeekBar seekBarSensor;
    private TextView txtStiffnessValue;
    private TextView txtSensorValue;
    private float sensorStrength;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        //get from layout
        fluidView = findViewById(R.id.fluidView);
        panelControls = findViewById(R.id.panelControls);
        btnToggleMenu = findViewById(R.id.btnToggleMenu);
        btnClose = findViewById(R.id.btnClose);

        checkBoxParticle = findViewById(R.id.checkParticle);
        checkBoxFPS = findViewById(R.id.checkFPS);
        checkBoxGrid = findViewById(R.id.checkGrid);
        checkBoxPixel = findViewById(R.id.checkPixel);

        seekBarStiffness = findViewById(R.id.seekStiffness);
        seekBarSensor = findViewById(R.id.seekSensor);

        txtStiffnessValue = findViewById(R.id.txtStiffnessValue);
        txtSensorValue = findViewById(R.id.txtSensorValue);

        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        if (sensorManager != null) {
            accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        }


        //initial settings
        checkBoxParticle.setChecked(false);
        checkBoxPixel.setChecked(true);
        checkBoxFPS.setChecked(true);
        checkBoxGrid.setChecked(false);
        seekBarStiffness.setProgress(20);
        seekBarSensor.setProgress(25);
        txtStiffnessValue.setText(String.valueOf(seekBarStiffness.getProgress()/10f));
        txtSensorValue.setText(String.valueOf(seekBarSensor.getProgress()*2));
        sensorStrength = 50f;


        // sync fluidView
        fluidView.setRenderPixel(checkBoxPixel.isChecked());
        fluidView.setRenderParticle(checkBoxParticle.isChecked());
        fluidView.setShowFPS(checkBoxFPS.isChecked());
        fluidView.setShowGrid(checkBoxGrid.isChecked());
        fluidView.setDensityStiffness(seekBarStiffness.getProgress()/10f);


        // Events
        // Khi bấm nút Settings -> Hiện menu, ẩn nút Settings
        btnToggleMenu.setOnClickListener(v -> {
            panelControls.setVisibility(View.VISIBLE);
            btnToggleMenu.setVisibility(View.GONE);
        });

        // Khi bấm nút Close -> Ẩn menu, hiện lại nút Settings
        btnClose.setOnClickListener(v -> {
            panelControls.setVisibility(View.GONE);
            btnToggleMenu.setVisibility(View.VISIBLE);
        });

        checkBoxParticle.setOnCheckedChangeListener(
                ((buttonView, isChecked) -> fluidView.setRenderParticle(isChecked))
        );
        checkBoxPixel.setOnCheckedChangeListener(
                ((buttonView, isChecked) -> fluidView.setRenderPixel(isChecked))
        );
        checkBoxFPS.setOnCheckedChangeListener(
                ((buttonView, isChecked) -> fluidView.setShowFPS(isChecked))
        );
        checkBoxGrid.setOnCheckedChangeListener(
                ((buttonView, isChecked) -> fluidView.setShowGrid(isChecked))
        );

        seekBarStiffness.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                float stiffness = progress/10f;
                fluidView.setDensityStiffness(stiffness);
                txtStiffnessValue.setText(String.valueOf(stiffness));
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {

            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {

            }
        });

        seekBarSensor.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                float gravityMultiply = (float) (progress*2);
                sensorStrength = gravityMultiply;
                txtSensorValue.setText(String.valueOf(gravityMultiply));
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {

            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {

            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (accelerometer != null) {
            sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_GAME);
        }

        if (fluidView != null) {
            fluidView.startSimulation();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        sensorManager.unregisterListener(this);

        if (fluidView != null) {
            fluidView.stopSimulation();
        }
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
            float gx = -event.values[0] * sensorStrength;
            float gy = event.values[1] * sensorStrength;

            fluidView.setForce(gx, gy);
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}
}