package com.example.ble1507;

import android.util.Log;

import java.util.concurrent.Callable;

import jp.co.sony.sprbox.imuadv.internal.InertialMotionLib;

/**
 * Java adapter for the SDK's Kotlin-internal JNI singleton.
 *
 * The old implementation declared a second set of native methods under this
 * package, so Android could never find their JNI symbols. Java intentionally
 * delegates to the pinned SDK JVM class, whose native names match the AAR.
 */
public final class InertialMotionLib2Compat {
    private static final String TAG = "SprightImuBridge";
    private final InertialMotionLib delegate = InertialMotionLib.INSTANCE;
    private volatile String lastError;

    public boolean initialize(float imuRate) {
        return callBoolean("initialize", () -> delegate.initialize(imuRate));
    }

    public void release() {
        run("release", delegate::release);
    }

    public boolean updateImu(float[] accel, float[] gyro, double timestamp, float temperature) {
        return callBoolean(
                "updateImu",
                () -> delegate.updateImu(accel, gyro, timestamp, temperature)
        );
    }

    public float[] getEulerAngle() {
        return call("getEulerAngle", delegate::getEulerAngle, null);
    }

    public boolean setMode(int mode) {
        return callBoolean("setMode", () -> delegate.setMode(mode));
    }

    public boolean setQuaternion(float[] quaternion) {
        return callBoolean("setQuaternion", () -> delegate.setQuaternion(quaternion));
    }

    public boolean setCalibParam(float[] param, int kind) {
        return callBoolean("setCalibParam", () -> delegate.setCalibParam(param, kind));
    }

    public boolean setGravityNorm(double gravityNorm) {
        return callBoolean("setGravityNorm", () -> delegate.setGravityNorm(gravityNorm));
    }

    public boolean setGravityAttitude(float[] gravity) {
        return callBoolean("setGravityAttitude", () -> delegate.setGravityAttitude(gravity));
    }

    public boolean startUpdatePositionAttitude() {
        return callBoolean(
                "startUpdatePositionAttitude",
                delegate::startUpdatePositionAttitude
        );
    }

    public boolean finishUpdatePositionAttitude() {
        return callBoolean(
                "finishUpdatePositionAttitude",
                delegate::finishUpdatePositionAttitude
        );
    }

    public boolean startCalib(int calibKind) {
        return callBoolean("startCalib", () -> delegate.startCalib(calibKind));
    }

    public boolean finishCalib() {
        return callBoolean("finishCalib", () -> delegate.finishCalib() != null);
    }

    public String getLastError() {
        return lastError;
    }

    private boolean callBoolean(String operation, Callable<Boolean> callable) {
        try {
            boolean result = Boolean.TRUE.equals(callable.call());
            if (!result) {
                lastError = operation + " returned false";
                Log.e(TAG, lastError);
                return false;
            }
            lastError = null;
            return true;
        } catch (Throwable error) {
            lastError = operation + ": " + error.getClass().getSimpleName() + ": " + error.getMessage();
            Log.e(TAG, lastError, error);
            return false;
        }
    }

    private <T> T call(String operation, Callable<T> callable, T fallback) {
        try {
            T result = callable.call();
            lastError = null;
            return result;
        } catch (Throwable error) {
            lastError = operation + ": " + error.getClass().getSimpleName() + ": " + error.getMessage();
            Log.e(TAG, lastError, error);
            return fallback;
        }
    }

    private void run(String operation, Runnable runnable) {
        try {
            runnable.run();
            lastError = null;
        } catch (Throwable error) {
            lastError = operation + ": " + error.getClass().getSimpleName() + ": " + error.getMessage();
            Log.e(TAG, lastError, error);
        }
    }
}
