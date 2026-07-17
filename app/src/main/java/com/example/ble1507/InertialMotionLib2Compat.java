package com.example.ble1507;

public class InertialMotionLib2Compat {
    static {
        System.loadLibrary("inertialmotionlib2");
        System.loadLibrary("inertialmotionlib2_jni");
    }

    public native boolean initialize(float latitude, float imuRate);

    public native void release();

    public native boolean updateImu(float[] accel, float[] gyro, float timestamp, float temperature);

    public native float[] getEulerAngle();

    public native void setMode(int mode);

    public native boolean startUpdatePositionAttitude();

    public native boolean finishUpdatePositionAttitude();

    public native boolean startCalib(int calibKind);

    public native float[] finishCalib();
}
