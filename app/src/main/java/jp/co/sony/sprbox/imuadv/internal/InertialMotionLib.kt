package jp.co.sony.sprbox.imuadv.internal

internal object InertialMotionLib {
    init {
        System.loadLibrary("inertialmotionlib2_jni")
    }

    external fun initialize(imuRate: Float): Boolean
    external fun release()
    external fun setMode(mode: Int): Boolean
    external fun setGravityNorm(gravityNorm: Double): Boolean
    external fun setCalibParam(param: FloatArray, kind: Int): Boolean
    external fun setGravityAttitude(gravity: FloatArray): Boolean
    external fun setQuaternion(quaternion: FloatArray): Boolean
    external fun setVelocity(velocity: FloatArray): Boolean
    external fun setPosition(position: FloatArray): Boolean
    external fun updateImu(accel: FloatArray, gyro: FloatArray, timestamp: Double, temperature: Float): Boolean
    external fun getEstimateSnapshot(): FloatArray
    external fun getQuaternion(): FloatArray
    external fun getEulerAngle(): FloatArray
    external fun getPosition(): FloatArray
    external fun getVelocity(): FloatArray
    external fun getStatus(): Int
    external fun startUpdatePositionAttitude(): Boolean
    external fun finishUpdatePositionAttitude(): Boolean
    external fun startCalib(kind: Int): Boolean
    external fun finishCalib(): CalibResult?
    external fun getCalibParam(kind: Int): CalibParamResult?
}

internal class CalibResult {
    @JvmField var gyroBiasCalibLevel: Int = 0
    @JvmField var gyroBiasError: Float = 0f
    @JvmField var levelingCalibLevel: Int = 0
    @JvmField var levelingError: Float = 0f
}

internal class CalibParamResult {
    @JvmField var param: FloatArray = FloatArray(3)
    @JvmField var error: Float = 0f
}
