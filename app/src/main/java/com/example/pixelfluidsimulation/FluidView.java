package com.example.pixelfluidsimulation;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class FluidView extends View {
    private List<Particle> particles = new ArrayList<>();
    private Paint paint = new Paint();
    private Paint boxPaint = new Paint(); // 1. KHAI BÁO CÂY BÚT VẼ KHUNG
    private float gx = 0, gy = 0;
    private boolean initialized = false;

    public FluidView(Context context, AttributeSet attrs) {
        super(context, attrs);
        boxPaint.setColor(Color.WHITE);
        boxPaint.setStyle(Paint.Style.STROKE);
        boxPaint.setStrokeWidth(5f);
    }

    private void initParticles(int count) {
        Random r = new Random();
        int[] colors = {Color.CYAN, Color.BLUE, Color.parseColor("#00E5FF"), Color.WHITE};
        for (int i = 0; i < count; i++) {
            particles.add(new Particle(
                    r.nextFloat() * 800,
                    r.nextFloat() * 800,
                    8f,
                    colors[r.nextInt(colors.length)]
            ));
        }
    }

    public void setGravity(float x, float y) {
        this.gx = x;
        this.gy = y;
        invalidate(); // Thông báo Android vẽ lại Frame mới
    }

    private void handleParticleCollisions() {
        float stiffness = 0.8f; // Độ cứng (giảm xuống để hạt không đẩy nhau quá hăng)

        for (int i = 0; i < particles.size(); i++) {
            Particle p1 = particles.get(i);
            for (int j = i + 1; j < particles.size(); j++) {
                Particle p2 = particles.get(j);

                float dx = p2.x - p1.x;
                float dy = p2.y - p1.y;
                // Dùng khoảng cách bình phương để máy chạy nhanh hơn (không cần căn bậc 2)
                float distSq = dx * dx + dy * dy;
                float minDist = p1.radius + p2.radius;
                float minDistSq = minDist * minDist;

                if (distSq < minDistSq) {
                    float dist = (float) Math.sqrt(distSq);
                    if (dist == 0) continue; // Tránh lỗi chia cho 0

                    // Tính độ lún
                    float overlap = minDist - dist;

                    // Vector hướng đẩy
                    float nx = dx / dist;
                    float ny = dy / dist;

                    // CHỈ ĐẨY NHẸ (Dùng hệ số stiffness)
                    // Thay vì đẩy văng ra ngay lập tức, ta dịch chuyển từ từ
                    float moveX = nx * overlap * stiffness;
                    float moveY = ny * overlap * stiffness;

                    p1.x -= moveX;
                    p1.y -= moveY;
                    p2.x += moveX;
                    p2.y += moveY;

                    // GIẢM VẬN TỐC (Để chúng đứng yên khi chồng lên nhau)
                    // Nhân với 0.5f để triệt tiêu lực phản chấn gây rung
                    p1.vx *= 0.5f;
                    p1.vy *= 0.5f;
                    p2.vx *= 0.5f;
                    p2.vy *= 0.5f;
                }
            }
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int screenW = getWidth();
        int screenH = getHeight();
        if (screenW == 0 || screenH == 0) return;

        // 1. Xác định kích thước ô vuông (ví dụ: 600x600 pixel)
        float boxSize = 600f;
        float left = (screenW - boxSize) / 2;
        float top = (screenH - boxSize) / 2;
        float right = left + boxSize;
        float bottom = top + boxSize;

//        float left = 0;
//        float top = 0;
//        float right = screenW;
//        float bottom = screenH;

        // 2. Khởi tạo hạt LẦN ĐẦU TIÊN bên trong khung
        if (!initialized) {
            Random r = new Random();

            int[] vibrantColors = {
                    Color.parseColor("#FF1744"), // Đỏ Neon
                    Color.parseColor("#FFEA00"), // Vàng Chanh
                    Color.parseColor("#00E676"), // Xanh Lá Neon
                    Color.parseColor("#00B0FF"), // Xanh Dương Sáng
                    Color.parseColor("#D500F9"), // Tím Neon
                    Color.parseColor("#FF9100")  // Cam Sáng
            };

            for (int i = 0; i < 200; i++) {
                int randomColor = vibrantColors[r.nextInt(vibrantColors.length)];
                particles.add(new Particle(
                        left + r.nextFloat() * boxSize,
                        top + r.nextFloat() * boxSize,
                        8f, randomColor));
            }
            initialized = true;
        }

        canvas.drawColor(Color.parseColor("#121212")); // Nền xám đậm

        // 3. Vẽ cái khung ô vuông để dễ quan sát
        canvas.drawRect(left, top, right, bottom, boxPaint);

        handleParticleCollisions();
        // 4. Cập nhật và vẽ hạt
        for (Particle p : particles) {
            p.update(gx, gy, left, top, right, bottom);
            paint.setColor(p.color);
            canvas.drawCircle(p.x, p.y, p.radius, paint);
        }
        invalidate();
    }
}