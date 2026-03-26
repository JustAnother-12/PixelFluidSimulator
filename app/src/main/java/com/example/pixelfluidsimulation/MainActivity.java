package com.example.pixelfluidsimulation;

import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.widget.CheckBox;
import android.widget.SeekBar;

import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity implements SensorEventListener {

    private SensorManager sensorManager;
    private Sensor accelerometer;
    private PixelFluidView fluidView;
    private CheckBox checkBoxParticle;
    private CheckBox checkBoxPixel;
    private CheckBox checkBoxFPS;
    private CheckBox checkBoxGrid;
    private SeekBar seekBarStiffness;
    private SeekBar seekBarGravity;
    private float sensorStrength;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);


        //get from layout
        fluidView = findViewById(R.id.fluidView);

        checkBoxParticle = findViewById(R.id.checkParticle);
        checkBoxFPS = findViewById(R.id.checkFPS);
        checkBoxGrid = findViewById(R.id.checkGrid);
        checkBoxPixel = findViewById(R.id.checkPixel);

        seekBarStiffness = findViewById(R.id.seekStiffness);
        seekBarGravity = findViewById(R.id.seekGravity);

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
        seekBarGravity.setProgress(20);
        sensorStrength = 20f;


        // sync fluidView
        fluidView.setRenderPixel(true);
        fluidView.setRenderParticle(false);
        fluidView.setShowFPS(true);
        fluidView.setShowGrid(false);
        fluidView.setDensityStiffness(0.2f);


        // Events
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
                float stiffness = progress/100f;
                fluidView.setDensityStiffness(stiffness);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {

            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {

            }
        });

        seekBarGravity.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                float gravityMultiply = (float) progress;
                sensorStrength = gravityMultiply;
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

            fluidView.setGravity(gx, gy);
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}
}