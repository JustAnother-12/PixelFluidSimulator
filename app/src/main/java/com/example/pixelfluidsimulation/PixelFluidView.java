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

import kotlin.jvm.Synchronized;

public class PixelFluidView extends View {
    // Debug flags
    private boolean renderParticle = false;
    private boolean renderPixel = false;
    private boolean showGrid = false;
    private boolean showFPS = false;

    // FPS counter
    private long lastTime = 0;
    private float currentFPS = 0;

    // particles
    private List<Particle> particles = new ArrayList<>();
    private int maxParticles = 1000;
    private float particleRadius = 0.1f;

    // grid
    private int cols = 50;
    private int rows = 80;

    private float[][] u;
    private float[][] v;

    private float[][] uPrev;
    private float[][] vPrev;

    private float[][] weightU = new float[cols + 1][rows];
    private float[][] weightV = new float[cols][rows + 1];

    private int numPressureIters = 60; // Số vòng lặp giải áp suất
    private float overRelaxation = 1.9f; // Hệ số hội tụ nhanh (1.0 -> 2.0)
    private float restDensity = (float) (maxParticles / ((cols * rows)*0.2));
    private float densityStiffness = 2.0f; // Độ cứng của mật độ
    private int[][] cellType;
    private float[][] particleDensity;

    // Constants for cellType
    private static final int AIR = 0;
    private static final int FLUID = 1;
    private static final int SOLID = 2;

    // for Spatial Hashing
    private int[] cellCount;
    private int[] firstParticle;
    private int[] nextParticle;

    //gravity
    private float gx = 0, gy = 0;

    //damping
    float damping = 0.98f;

    //paint
    private Paint paint;
    private Paint uiPaint;
    private Paint pixelPaint;
    private Paint particlePaint;
    private float[][] pixelBrightness = new float[cols][rows];
    private float decayRate = 0.65f; // speed for brightness decaying (0.0 -> 1.0)
    private float minBrightness = 0.05f;

    // verticies
    private int numQuads = cols * rows; // number of cells
    private int numVerticies = numQuads * 4; // each quad has 4 points for vertices
    private int numIndices = numQuads * 6; // each quad has 6 numbers to tell the GPU draw order

    private float[] vertices = new float[numVerticies*2]; //x and y
    private int[] colors = new int[numVerticies]; // 1 color or each point
    private short[] indices = new short[numIndices];

    //delta time
    float dt = 0.016f; // ~60fps

    //simulation
    private boolean isRunning = false;
    private Thread physicsThread;
    private final Object syncLock = new Object();

    public PixelFluidView(Context context) {
        super(context);
        init();
    }

    public PixelFluidView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public PixelFluidView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    public void startSimulation() {
        if (!isRunning) {
            isRunning = true;
            physicsThread = new Thread(()->{
                long targetTimeMillis = 16; //~60FPS
                while(isRunning){
                    long startTime = System.currentTimeMillis();

                    //Lock syncing
                    synchronized (syncLock){
                        update(dt);
                    }
                    //draw with background thread
                    postInvalidate();

                    // Tính toán thời gian ngủ để cân bằng tốc độ (Frame pacing)
                    long timeTaken = System.currentTimeMillis() - startTime;
                    long sleepTime = targetTimeMillis - timeTaken;

                    if (sleepTime > 0) {
                        try {
                            Thread.sleep(sleepTime);
                        } catch (InterruptedException e) {
                            break;
                        }
                    }
                }
            });
            physicsThread.start();
        }
    }

    public void stopSimulation() {
        isRunning = false;
        if(physicsThread != null){
            try{
                physicsThread.join(); //wait for the thread to end
            }catch (InterruptedException e){
                e.printStackTrace();
            }
            physicsThread = null;
        }
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

        // paints
        paint = new Paint();
        paint.setAntiAlias(false);

        // UI Paint for FPS counter
        uiPaint = new Paint();
        uiPaint.setColor(Color.YELLOW);
        uiPaint.setTextSize(50);

        // Pixel paint for pixel render
        pixelPaint = new Paint();

        // Particle paint for particle render
        particlePaint = new Paint();

        //indices
        initIndices();

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
                    particleRadius
            ));
        }

        isRunning = false;
    }

    private void initIndices(){
        for(int i = 0; i < numQuads; i++){
            int vStart = i * 4;
            int iStart = i * 6;

            //init values
            //first tri
            indices[iStart] = (short) vStart;
            indices[iStart + 1] = (short) (vStart + 1);
            indices[iStart + 2] = (short) (vStart + 2);

            //second tri
            indices[iStart + 3] = (short) (vStart + 1);
            indices[iStart + 4] = (short) (vStart + 2);
            indices[iStart + 5] = (short) (vStart + 3);
        }
    }

    private void updatePixelBrightness() {
        //density threshold
        float threshold = 0.15f;

        for (int i = 1; i < cols; i++) {
            for (int j = 1; j < rows; j++) {
                    if (particleDensity[i][j] > threshold) {
                        // give full brightness if there's particle
                        pixelBrightness[i][j] = 1.0f;
                    } else{
                        // reduce if there's no particle
                        pixelBrightness[i][j] = Math.max(minBrightness, pixelBrightness[i][j] * decayRate);
                    }
            }
        }
    }

    //update for particles
    private void update(float dt){
        // apply force and move particles
        for(Particle p : particles) {
            p.vx += gx * dt;
            p.vy += gy * dt;

            // Damping (friction)
            p.vx *= damping;
            p.vy *= damping;

            p.x += p.vx * dt;
            p.y += p.vy * dt;
        }

        pushParticlesApart(1); checkNaN("After Push");

        handleCollisions();
        checkNaN("After Collisions");

        updateCellType();

        clearGrid();
        particlesToGrid();

        saveGrid();

        solveIncompressibility();
        checkNaN("After Solve");

        gridToParticles(); checkNaN("After GridToParticles");

        updatePixelBrightness();
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

    private void handleCollisions() {
        // Tường dày 1 ô, cộng thêm bán kính hạt để hạt nằm gọn trên mặt sàn
        float radius = particleRadius;
        float minX = 1.0f + radius;
        float maxX = (cols - 1) - radius;
        float minY = 1.0f + radius;
        float maxY = (rows - 1) - radius;

        for(Particle p : particles){

            // Xử lý va chạm biên: Ép vị trí và triệt tiêu vận tốc
            if (p.x < minX) {
                p.x = minX;
                p.vx = 0.0f;
            } else if (p.x > maxX) {
                p.x = maxX;
                p.vx = 0.0f;
            }

            if (p.y < minY) {
                p.y = minY;
                p.vy = 0.0f;
            } else if (p.y > maxY) {
                p.y = maxY;
                p.vy = 0.0f;
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
            float xU = p.x;
            float yU = p.y - halfH;

            int iU = (int)xU;
            int jU = (int)yU;

            iU = Math.max(0, Math.min(iU, cols - 2));
            jU = Math.max(0, Math.min(jU, rows - 2));

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
            float xV = p.x - halfH;
            float yV = p.y;

            int iV = (int)xV;
            int jV = (int)yV;

            iV = Math.max(0, Math.min(iV, cols - 2));
            jV = Math.max(0, Math.min(jV, rows - 2));

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


            // --- Cập nhật mật độ hạt mượt mà (Smooth Density) ---
            // Mật độ nên được tính tại tâm ô (giống như áp suất)
            float x = clamp(p.x, 0.5f, cols - 1.5f);
            float y = clamp(p.y, 0.5f, rows - 1.5f);

            int i = (int)(x - 0.5f);
            int j = (int)(y - 0.5f);
            float fx = (x - 0.5f) - i;
            float fy = (y - 0.5f) - j;

            // Trọng số bilinear cho 4 ô xung quanh tâm
            particleDensity[i][j]     += (1 - fx) * (1 - fy);
            particleDensity[i + 1][j] += fx * (1 - fy);
            particleDensity[i][j + 1] += (1 - fx) * fy;
            particleDensity[i + 1][j + 1] += fx * fy;
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
        float spacing = 0.8f; // particle spacing
        float minDist = spacing;
        float minDistSq = minDist * minDist;

        for (int iter = 0; iter < numIters; iter++) {
            // Build Spatial Hash
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
        canvas.drawColor(Color.rgb(10, 10, 20));

        synchronized (syncLock){
            paint.setStyle(Paint.Style.FILL);

            //draw walls
            for (int i = 0; i < cols; i++) {
                for (int j = 0; j < rows; j++) {
                    if (cellType[i][j] == SOLID) { // draw SOLIDS
                        paint.setColor(Color.argb(50, 255, 255, 255));
                        canvas.drawRect(
                                i * cellSize, j * cellSize,
                                (i + 1) * cellSize, (j + 1) * cellSize,
                                paint
                        );
                    }
                }
            }

            // draw grid
            if (showGrid) {
                drawGrid(canvas, cellSize);
                drawDensityCells(canvas, cellSize);
            }

            // draw the particles
            if(renderParticle)
                drawParticles(canvas, cellSize);

            // draw the pixels
            if(renderPixel)
//                drawPixels(canvas, cellSize);
                drawVerticies(canvas, cellSize);

        }

        // draw FPS
        if (showFPS) {
            drawFPS(canvas);
        }
    }

    private void drawFPS(Canvas canvas){
        calculateFPS();
        uiPaint.setAntiAlias(true);
        uiPaint.setStyle(Paint.Style.FILL);
        uiPaint.setColor(Color.YELLOW);
        uiPaint.setTextSize(50);
        canvas.drawText("FPS: " + (int)currentFPS, 50, 100, uiPaint);
    }
    private void drawGrid(Canvas canvas, float cellSize){
        paint.setColor(Color.rgb(94, 95, 97));
        paint.setStrokeWidth(1f);
        //columns
        for (int i = 1; i < cols; i++) {
            canvas.drawLine(i * cellSize, cellSize, i * cellSize, (rows - 1) * cellSize, paint);
        }
        for (int j = 1; j < rows; j++) {
            canvas.drawLine(cellSize, j * cellSize, (cols - 1) * cellSize, j * cellSize, paint);
        }
    }

    private void drawParticles(Canvas canvas, float cellSize){
        particlePaint.setStyle(Paint.Style.FILL);
        for (Particle p : particles) {
            float cx = p.x * cellSize;
            float cy = p.y * cellSize;
            particlePaint.setColor(Color.GREEN);
            canvas.drawCircle(cx, cy, p.radius * cellSize, particlePaint);
        }
    }

    private void drawPixels(Canvas canvas, float cellSize) {
        float spacing = 4f;
        float pixelSize = cellSize - spacing;

        for (int i = 0; i < cols; i++) {
            for (int j = 0; j < rows; j++) {
                float brightness = pixelBrightness[i][j];
                float cx = (i + 0.5f) * cellSize;
                float cy = (j + 0.5f) * cellSize;

                // Glow layer
                if (brightness > 0.3f) {
                    pixelPaint.setStyle(Paint.Style.FILL);
                    pixelPaint.setColor(Color.argb((int)(brightness * 40), 0, 255, 200));
                    canvas.drawCircle(cx, cy, cellSize * 0.8f, pixelPaint);
                }

                // Core layer
                // color change based on brightness
                int alpha = (int) (brightness * 255);
                // display tint grey if brightness = minBrightness
                if (brightness <= minBrightness) {
                    pixelPaint.setColor(Color.argb(30, 50, 50, 80));
                } else {
                    pixelPaint.setColor(Color.argb(alpha, 0, 255, 220));
                }

                pixelPaint.setStyle(Paint.Style.FILL);
                canvas.drawRect(
                        cx - pixelSize/2, cy - pixelSize/2,
                        cx + pixelSize/2, cy + pixelSize/2,
                        pixelPaint
                );
            }
        }
    }

    private void drawVerticies(Canvas canvas, float cellSize){
        float halfSize = (cellSize - 4f) / 2f;

        for(int i = 0; i < cols; i++){
            for(int j = 0; j < rows; j++){
                float brightness = pixelBrightness[i][j];
                float cx = (i + 0.5f) * cellSize;
                float cy = (j + 0.5f) * cellSize;

                int n = j * cols + i; // cell's inter position in the grid
                int vStart = n * 8; // verticy start
                int cStart = n * 4; // color start

                //glow layer


                //core layer
                // set values for 4 verticies edges
                vertices[vStart] = cx - halfSize; vertices[vStart + 1] = cy - halfSize;
                vertices[vStart + 2] = cx + halfSize; vertices[vStart + 3] = cy - halfSize;
                vertices[vStart + 4] = cx - halfSize; vertices[vStart + 5] = cy + halfSize;
                vertices[vStart + 6] = cx + halfSize; vertices[vStart + 7] = cy + halfSize;

                // colors
                int color;
                int alpha = (int) (brightness * 255);
                if (brightness <= minBrightness) {
                    color = Color.argb(30, 50, 50, 80);
                } else {
                    color = Color.argb(alpha, 0, 255, 220);
                }

                // give the same color for 4 edges
                colors[cStart] = colors[cStart + 1] = colors[cStart + 2] = colors[cStart + 3] = color;
            }
        }

        //draw all of the verticies
        canvas.drawVertices(
                Canvas.VertexMode.TRIANGLES,
                vertices.length,
                vertices,
                0,
                null,
                0,
                colors,
                0,
                indices,
                0,
                indices.length,
                pixelPaint
        );
    }
    private void drawDensityCells(Canvas canvas, float cellSize){
        // draw cells
        paint.setStyle(Paint.Style.FILL);
        for (int i = 0; i < cols; i++) {
            for (int j = 0; j < rows; j++) {
                if(cellType[i][j] == FLUID){
                    // density debug with color
                    float d = particleDensity[i][j] / restDensity; // Tỷ lệ mật độ
                    int alpha = (int)clamp(d * 100, 0, 100); // Mật độ càng cao ô càng sáng
                    paint.setColor(Color.argb(alpha, 255, 0, 0)); // Màu đỏ cảnh báo nén
                    canvas.drawRect(i * cellSize, j * cellSize, (i + 1) * cellSize, (j + 1) * cellSize, paint);
                }
            }
        }
    }

    private void calculateFPS() {
        long currentTime = System.nanoTime();
        if (lastTime != 0) {
            // Tính delta time theo giây: 1e9 nano giây = 1 giây
            float frameTime = (currentTime - lastTime) / 1_000_000_000f;
            currentFPS = (currentFPS * 0.9f) + ((1f / frameTime) * 0.1f); // Làm mượt số FPS
        }
        lastTime = currentTime;
    }

    public void setForce(float gx, float gy){
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

    // Toggle
    public void setShowGrid(boolean show) {
        this.showGrid = show;
        invalidate();
    }
    public void setShowFPS(boolean show) {
        this.showFPS = show;
        invalidate();
    }

    public void setRenderParticle(boolean render) {
        this.renderParticle = render;
        invalidate();
    }
    public void setRenderPixel(boolean render) {
        this.renderPixel = render;
        invalidate();
    }

    // Density Stiffness (0.0 -> 1.0)
    public void setDensityStiffness(float stiffness) {
        this.densityStiffness = stiffness;
    }
}