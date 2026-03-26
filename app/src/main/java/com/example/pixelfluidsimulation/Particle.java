package com.example.pixelfluidsimulation;

import android.graphics.Color;

public class Particle {
    public float x, y, radius;
    public float vx = 0, vy = 0;

    public Particle(float x, float y, float radius) {
        this.x = x;
        this.y = y;
        this.radius = radius;
    }
}