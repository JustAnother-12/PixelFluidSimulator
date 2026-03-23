package com.example.pixelfluidsimulation;

import android.graphics.Color;

public class Particle {
    public float x, y, vx, vy, radius;
    public int color;

    public Particle(float x, float y, float radius, int color) {
        this.x = x;
        this.y = y;
        this.radius = radius * 2.5f;
        this.color = color;
        this.vx = 0;
        this.vy = 0;
    }

    public void update(float gx, float gy, float left, float top, float right, float bottom) {
        vx += gx;
        vy += gy;
        vx *= 0.97f;
        vy *= 0.97f;
        x += vx;
        y += vy;

        // Kiểm tra và ép hạt quay lại nếu lỡ văng ra ngoài khung
        if (x < left + radius) { x = left + radius; vx *= -0.6f; }
        if (x > right - radius) { x = right - radius; vx *= -0.6f; }
        if (y < top + radius) { y = top + radius; vy *= -0.6f; }
        if (y > bottom - radius) { y = bottom - radius; vy *= -0.6f; }
    }
}