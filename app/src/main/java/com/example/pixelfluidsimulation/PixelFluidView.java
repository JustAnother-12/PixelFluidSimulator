package com.example.pixelfluidsimulation;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.logging.Handler;

public class PixelFluidView extends View {
    // particles
    private List<Particle> particles = new ArrayList<>();
    private int maxParticles = 400;

    // grid
    private int cols = 30;
    private int rows = 50;

    private float[][] u;
    private float[][] v;

    private float[][] uPrev;
    private float[][] vPrev;

    private float[][] weightU = new float[cols + 1][rows];
    private float[][] weightV = new float[cols][rows + 1];

    private int numPressureIters = 60; // Số vòng lặp giải áp suất
    private float overRelaxation = 1.9f; // Hệ số hội tụ nhanh (1.0 -> 2.0)
    private float restDensity = 0;       // Mật độ nghỉ (sẽ tính ở init)
    private float densityStiffness = 1.0f; // Độ cứng của mật độ

    //0 = solid, 1 = fluid, 2 = air
    private int[][] cellType;
    private float[][] particleDensity;

    // Constants for cellType
    private static final int AIR = 0;
    private static final int FLUID = 1;
    private static final int SOLID = 2;

    // mảng hỗ trợ Spatial Hashing
    private int[] cellCount;
    private int[] firstParticle;
    private int[] nextParticle;

    //gravity
    private float gx = 0, gy = 0;

    //paint
    private Paint paint;

    //delta time
    float dt = 0.02f; // ~60fps

    //simulation
    private boolean isRunning = false;
    private final Runnable simulationRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isRunning) return;

            update();      // Tính toán vật lý
            invalidate();  // Vẽ lại màn hình

            // lặp sau 16ms ~ 60FPS
            postDelayed(this, 16);
        }
    };

    public PixelFluidView(Context context) {
        super(context);
        init();
    }

    public void startSimulation() {
        if (!isRunning) {
            isRunning = true;
            post(simulationRunnable);
        }
    }

    public void stopSimulation() {
        isRunning = false;
        removeCallbacks(simulationRunnable);
    }

    private void init(){
        u = new float[cols + 1][rows];
        v = new float[cols][rows + 1];
        uPrev = new float[cols + 1][rows];
        vPrev = new float[cols][rows + 1];

        cellType = new int[cols][rows];
        particleDensity = new float[cols][rows];

        // Cấp phát cho Spatial Hashing (số lượng phần tử = maxParticles)
        cellCount = new int[cols * rows];
        firstParticle = new int[cols * rows];
        nextParticle = new int[maxParticles];

        paint = new Paint();

        restDensity = (float) (maxParticles / ((cols * rows)*0.3));
//        restDensity = 1.0f;
        if (restDensity <= 0) restDensity = 1.0f;

        //init border
        for (int i = 0; i < cols; i++) {
            for (int j = 0; j < rows; j++) {
                // Tạo tường bao quanh
                if (i == 0 || i == cols - 1 || j == 0 || j == rows - 1) {
                    cellType[i][j] = SOLID;
                } else {
                    cellType[i][j] = AIR;
                }
            }
        }

        //init particles
        for(int i = 0; i < maxParticles; i++){
            particles.add(new Particle(
                    1.5f + (float)(Math.random() * (cols - 3)), // Cách tường ít nhất 1 ô
                    1.5f + (float)(Math.random() * (rows - 3)),
                    0.6f
            ));
        }

        isRunning = false;
    }

    //update for particles
    private void update(){
        //apply gravity
        for(Particle p : particles) {
            p.vx += gx * dt;
            p.vy += gy * dt;
        }

        moveParticles(); checkNaN("After Move");

        updateCellType();

        clearGrid();
        particlesToGrid();

        saveGrid();

        solveIncompressibility();
        checkNaN("After Solve");
        checkGridSanity("A");

        gridToParticles(); checkNaN("After GridToParticles");

        pushParticlesApart(1); checkNaN("After Push");
    }

    private void updateCellType(){
        for(int i = 0; i < cols; i++){
            for(int j = 0; j < rows; j++){
                if(cellType[i][j] != SOLID)
                    cellType[i][j] = AIR;

                particleDensity[i][j] = 0; // reset density
            }
        }

        for(Particle p : particles){
            int i = (int)clamp(p.x, 0, cols - 1);
            int j = (int)clamp(p.y, 0, rows - 1);

            if(cellType[i][j] == AIR) cellType[i][j] = FLUID;
            particleDensity[i][j]++; // count particles in each cells
        }
    }

    private void saveGrid(){
        for(int i = 0; i < cols + 1; i++){
            for(int j = 0; j < rows; j++)
                uPrev[i][j] = u[i][j];
        }

        for(int i = 0; i < cols; i++){
            for(int j = 0; j < rows + 1; j++)
                vPrev[i][j] = v[i][j];
        }
    }

    private void moveParticles() {
        for(Particle p : particles){
//            p.vx += gx * dt;
//            p.vy += gy * dt;
            // Chặn vận tốc không cho quá nhanh (max 1 ô lưới / frame)
            p.vx = clamp(p.vx, -20f, 20f);
            p.vy = clamp(p.vy, -20f, 20f);

            // damping (friction)
            float damping = (float)Math.pow(0.98f, dt * 60f);
            p.vx *= damping;
            p.vy *= damping;

            // move the particles
            p.x += p.vx * dt;
            p.y += p.vy * dt;

            // Giới hạn cứng để hạt không bao giờ văng ra khỏi mảng
            float margin = 1.01f;
            if (p.x < margin) { p.x = margin; p.vx *= -0.1f; }
            if (p.x > cols - margin - 1) { p.x = cols - margin - 1; p.vx *= -0.1f; }
            if (p.y < margin) { p.y = margin; p.vy *= -0.1f; }
            if (p.y > rows - margin - 1) { p.y = rows - margin - 1; p.vy *= -0.1f; }

            // Va chạm với ô SOLID
            int i = (int)clamp(p.x, 0, cols - 1);
            int j = (int)clamp(p.y, 0, rows - 1);

            if(cellType[i][j] == SOLID){
                //push back to closest cell
                p.x -= p.vx * dt;
                p.y -= p.vy * dt;

                //set back velocity
                p.vx *= -0.1f;
                p.vy *= -0.1f;
            }

        }
    }
    private void gridToParticles() {
        float alpha = 0.95f;

        for (Particle p : particles) {

            Vec2 velNew = sampleVelocity(p.x, p.y);
            Vec2 velOld = sampleVelocityPrev(p.x, p.y);

            // FLIP (Fluid Implicit Particle)
            float flipX = p.vx + (velNew.x - velOld.x);
            float flipY = p.vy + (velNew.y - velOld.y);

            // PIC (Particle In Cell) - Chính là velNew

            // Blend PIC + FLIP
            p.vx = alpha * flipX + (1 - alpha) * velNew.x;
            p.vy = alpha * flipY + (1 - alpha) * velNew.y;

        }
    }

    private Vec2 sampleVelocityPrev(float x, float y) {
        float h2 = 0.5f;

        // sample U velocity (U lệch Y xuống 0.5)
        float xU = clamp(x, 0, cols - 1);
        float yU = clamp(y - h2, 0, rows - 2);
        int iU = (int)xU;
        int jU = (int)yU;
        float fxU = xU - iU;
        float fyU = yU - jU;
        float uVal = bilerp(uPrev[iU][jU], uPrev[iU+1][jU], uPrev[iU][jU+1], uPrev[iU+1][jU+1], fxU, fyU);

        // sample V velocity (V lệch X qua phải 0.5)
        float xV = clamp(x - h2, 0, cols - 2);
        float yV = clamp(y, 0, rows - 1);
        int iV = (int)xV;
        int jV = (int)yV;
        float fxV = xV - iV;
        float fyV = yV - jV;
        float vVal = bilerp(vPrev[iV][jV], vPrev[iV+1][jV], vPrev[iV][jV+1], vPrev[iV+1][jV+1], fxV, fyV);

        return new Vec2(uVal, vVal);
    }

    private Vec2 sampleVelocity(float x, float y) {
        float h2 = 0.5f;

        // sample U velocity (U lệch Y xuống 0.5)
        float xU = clamp(x, 0, cols - 1);
        float yU = clamp(y - h2, 0, rows - 2);
        int iU = (int)xU;
        int jU = (int)yU;
        float fxU = xU - iU;
        float fyU = yU - jU;
        float uVal = bilerp(u[iU][jU], u[iU+1][jU], u[iU][jU+1], u[iU+1][jU+1], fxU, fyU);

        // sample V velocity (V lệch X qua phải 0.5)
        float xV = clamp(x - h2, 0, cols - 2);
        float yV = clamp(y, 0, rows - 1);
        int iV = (int)xV;
        int jV = (int)yV;
        float fxV = xV - iV;
        float fyV = yV - jV;
        float vVal = bilerp(v[iV][jV], v[iV+1][jV], v[iV][jV+1], v[iV+1][jV+1], fxV, fyV);

        return new Vec2(uVal, vVal);
    }


    private float bilerp(float v00, float v10, float v01, float v11, float fx, float fy) {

        if (Float.isNaN(v00) || Float.isNaN(v10) || Float.isNaN(v01) || Float.isNaN(v11)) return 0;

        float w00 = (1 - fx) * (1 - fy);
        float w10 = fx * (1 - fy);
        float w01 = (1 - fx) * fy;
        float w11 = fx * fy;

        float val = w00 * v00 +
                    w10 * v10 +
                    w01 * v01 +
                    w11 * v11;
        return val;
    }


    private void solveIncompressibility(){

        // set velocity to zero if a particle is inside a SOLID cell
        for(int i = 0 ;i < cols; i++){
            for(int j = 0 ;j < rows; j++){
                if(cellType[i][j] == SOLID){
                    u[i][j] = u[i + 1][j] = 0;
                    v[i][j] = v[i][j + 1] = 0;
                }
            }
        }

        for(int iter = 0; iter < numPressureIters; iter++){
            // start with 1 and end with -1 to avoid solid cells
            for(int i = 1 ;i < cols - 1; i++){
                for(int j = 1 ;j < rows - 1; j++){

                    if (cellType[i][j] != FLUID) continue;
                    // compute divergence
                    // u[i][j] is the left edge, u[i+1][j] is the right edge
                    float d = (u[i + 1][j] -  u[i][j]) + (v[i][j + 1] - v[i][j]);

                    // drift compensation
                    float compression = particleDensity[i][j] - restDensity;// too many particles in one cell compare to rest state -> compressed
                    if(compression > 0) {
                        d -= densityStiffness * compression;
                    }

                    // count the edges that aren't walls
                    float s = 0;
                    s += (cellType[i - 1][j] != SOLID) ? 1 : 0;
                    s += (cellType[i + 1][j] != SOLID) ? 1 : 0;
                    s += (cellType[i][j - 1] != SOLID) ? 1 : 0;
                    s += (cellType[i][j + 1] != SOLID) ? 1 : 0;

                    if(s > 0){
                        //compute pressure
                        float p = -d / s;
                        p *= overRelaxation;

                        // update cell's velocity
                        if(cellType[i - 1][j] != SOLID) u[i][j] -= p;
                        if(cellType[i + 1][j] != SOLID) u[i + 1][j] += p;
                        if(cellType[i][j - 1] != SOLID) v[i][j] -= p;
                        if(cellType[i][j + 1] != SOLID) v[i][j + 1] += p;
                    }
                }
            }
        }
    }

    //Takes particles velocity and turn them into grid's velocity field
    private void particlesToGrid() {
        float halfH = 0.5f;

        for (Particle p : particles) {
            // process U Velocity (horizontal)
            // U nằm ở (i, j + 0.5), nên ta dịch Y đi 0.5 để khớp vị trí
            float xU = clamp(p.x, 0, cols - 1);
            float yU = clamp(p.y - halfH, 0, rows - 2);
            int iU = (int)xU;
            int jU = (int)yU;
            float fxU = xU - iU;
            float fyU = yU - jU;

            float w00U = (1 - fxU) * (1 - fyU);
            float w10U = fxU * (1 - fyU);
            float w01U = (1 - fxU) * fyU;
            float w11U = fxU * fyU;

            addU(iU,   jU,   p.vx, w00U);
            addU(iU + 1, jU,   p.vx, w10U);
            addU(iU,   jU + 1, p.vx, w01U);
            addU(iU + 1, jU + 1, p.vx, w11U);

            // process V Velocity (vertical)
            // V nằm ở (i + 0.5, j), nên ta dịch X đi 0.5 để khớp vị trí
            float xV = clamp(p.x - halfH, 0, cols - 2);
            float yV = clamp(p.y, 0, rows - 1);
            int iV = (int)xV;
            int jV = (int)yV;
            float fxV = xV - iV;
            float fyV = yV - jV;

            float w00V = (1 - fxV) * (1 - fyV);
            float w10V = fxV * (1 - fyV);
            float w01V = (1 - fxV) * fyV;
            float w11V = fxV * fyV;

            addV(iV, jV, p.vy, w00V);
            addV(iV + 1, jV,   p.vy, w10V);
            addV(iV,   jV + 1, p.vy, w01V);
            addV(iV + 1, jV + 1, p.vy, w11V);

            // --- Cập nhật mật độ hạt (Density) ---
            int di = (int)clamp(p.x, 0, cols - 1);
            int dj = (int)clamp(p.y, 0, rows - 1);
            particleDensity[di][dj] += 1.0f;
        }

        // normalize the velocity strength
        normalizeGrid();
    }

    private void normalizeGrid() {
        float epsilon = 0.000001f;
        for(int i = 0; i< cols + 1; i++){
            for(int j = 0; j< rows; j++){
                if (weightU[i][j] > epsilon)
                    u[i][j] /= weightU[i][j];
            }
        }
        for(int i = 0; i< cols; i++){
            for(int j = 0; j< rows + 1; j++){
                if (weightV[i][j] > epsilon)
                    v[i][j] /= weightV[i][j];
            }
        }
    }

    private void addV(int i, int j, float val, float w) {
        if (i >= 0 && i < cols && j >= 0 && j < rows) {
            v[i][j] += val * w; // particle's velocity multiply by it's neighbor's weight
            weightV[i][j] += w; // saving the weightSum to normalize later
        }
    }

    private void addU(int i, int j, float val, float w) {
        if (i >= 0 && i < cols && j >= 0 && j < rows) {
            u[i][j] += val * w; // particle's velocity multiply by it's neighbor's weight
            weightU[i][j] += w; // saving the weightSum to normalize later
        }
    }

    private void clearGrid(){
        for(int i = 0; i< cols + 1; i++){
            for(int j = 0; j< rows; j++){
                u[i][j] = 0;
                weightU[i][j] = 0;
            }
        }
        for(int i = 0; i< cols; i++){
            for(int j = 0; j< rows + 1; j++){
                v[i][j] = 0;
                weightV[i][j] = 0;
            }
        }
    }

    private void pushParticlesApart(int numIters) {
        float spacing = 0.8f; // Khoảng cách lý tưởng giữa các hạt (đơn vị ô)
        float minDist = spacing;
        float minDistSq = minDist * minDist;

        for (int iter = 0; iter < numIters; iter++) {
            // Bước A: Xây dựng Spatial Hash
            Arrays.fill(cellCount, 0);
            for (Particle p : particles) {
                int cx = (int)clamp(p.x, 0, cols - 1);
                int cy = (int)clamp(p.y, 0, rows - 1);
                cellCount[cx * rows + cy]++;
            }

            // Tính vị trí bắt đầu của mỗi ô trong mảng phẳng
            int start = 0;
            for (int i = 0; i < cols * rows; i++) {
                firstParticle[i] = start;
                start += cellCount[i];
                cellCount[i] = 0; // reset để dùng làm offset ở bước sau
            }

            // Điền chỉ số hạt vào danh sách
            for (int i = 0; i < particles.size(); i++) {
                Particle p = particles.get(i);
                int cx = (int)clamp(p.x, 0, cols - 1);
                int cy = (int)clamp(p.y, 0, rows - 1);
                int cellIdx = cx * rows + cy;
                nextParticle[firstParticle[cellIdx] + cellCount[cellIdx]] = i;
                cellCount[cellIdx]++;
            }

            // Bước B: Đẩy hạt
            for (int i = 0; i < particles.size(); i++) {
                Particle p1 = particles.get(i);
                int px = (int)p1.x;
                int py = (int)p1.y;

                // Kiểm tra 9 ô xung quanh (ô hiện tại + 8 hàng xóm)
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dy = -1; dy <= 1; dy++) {
                        int nx = px + dx;
                        int ny = py + dy;
                        if (nx < 0 || nx >= cols || ny < 0 || ny >= rows) continue;

                        int cellIdx = nx * rows + ny;
                        for (int j = 0; j < cellCount[cellIdx]; j++) {
                            int p2Idx = nextParticle[firstParticle[cellIdx] + j];
                            if (p2Idx <= i) continue; // Tránh kiểm tra trùng hoặc chính nó

                            Particle p2 = particles.get(p2Idx);
                            float diffX = p1.x - p2.x;
                            float diffY = p1.y - p2.y;
                            float distSq = diffX * diffX + diffY * diffY;

                            if (distSq < minDistSq && distSq > 0.0001f) {
                                float dist = (float)Math.sqrt(distSq);
                                float s = (minDist - dist) / dist * 0.5f;
                                float pushX = diffX * s;
                                float pushY = diffY * s;
                                p1.x += pushX; p1.y += pushY;
                                p2.x -= pushX; p2.y -= pushY;
                            }
                        }
                    }
                }
            }
        }
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        super.onDraw(canvas);

        float cellSize = getWidth() / (float) cols;

        // draw background
        canvas.drawColor(Color.BLACK);

        // draw the particles
        for(Particle p : particles){
            float cx = p.x * cellSize;
            float cy = p.y * cellSize;

            paint.setColor(Color.CYAN);
            canvas.drawCircle(cx, cy, p.radius * (cellSize), paint);
        }
    }

    public void setGravity(float gx, float gy){
        this.gx = gx;
        this.gy = gy;
    }

    private float clamp(float val, float min, float max){
        if(val < min)
            return min;
        else if(val > max)
            return max;
        else
            return val;
    }

    private void checkNaN(String location) {
        for (Particle p : particles) {
            if (Float.isNaN(p.x) || Float.isNaN(p.vx)) {
                Log.e("FluidError", "NaN detected at: " + location);
                init(); // Reset lại toàn bộ simulation để chạy tiếp
                break;
            }
        }
    }

    private void checkGridSanity(String tag) {
        for (int i = 0; i <= cols; i++) {
            for (int j = 0; j < rows; j++) {
                if (Float.isNaN(u[i][j]) || Float.isInfinite(u[i][j])) {
                    Log.e("FluidDebug", tag + ": u[" + i + "][" + j + "] is " + u[i][j]);
                }
            }
        }
    }
}