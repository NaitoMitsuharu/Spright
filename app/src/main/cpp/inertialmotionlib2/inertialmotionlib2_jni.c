#include <jni.h>
#include "InertialMotionLib2.h"
#include "IML_Common.h"
#include <android/log.h>
#include <string.h>
#include <math.h>

#define LOG_TAG "InertialMotionLib2"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

/*
 * The distributed libinertialmotionlib2.so uses its internal singleton.
 * Although the public header exposes IML_INTERFACE, this binary returns
 * IML_RESULT_FAIL_INVALIDVALUE when a caller-owned handle is supplied.
 * SpresenseDroidPractice also passes NULL consistently for this same binary.
 */
#define IML_INSTANCE NULL
static jboolean g_isInitialized = JNI_FALSE;
static int g_initializeRefCount = 0;
static int g_getStatusFailureLogCount = 0;

// 定数定義
#define IML2_DEFAULT_GRAVITY_NORM 9.7975962
#define IML2_DEFAULT_LATITUDE 35.4437f

JNIEXPORT jboolean JNICALL
Java_jp_co_sony_sprbox_imuadv_internal_InertialMotionLib_initialize(JNIEnv *env, jobject thiz, jfloat imuRate) {
    if (g_isInitialized) {
        g_initializeRefCount++;
        return JNI_TRUE; // 既に初期化済み
    }

    IML_RESULT res = Iml2_Create(IML_INSTANCE, (IML_FLOAT32)imuRate);
    if (res == IML_RESULT_SUCCESS) {
        g_isInitialized = JNI_TRUE;

        // デフォルト設定
        IML_FLOAT64 gravitynorm = IML2_DEFAULT_GRAVITY_NORM;
        IML_RESULT gravityNormRes = Iml2_SetGravityNorm(IML_INSTANCE, gravitynorm);
        IML_RESULT latitudeRes = Iml2_SetLatitude(IML_INSTANCE, (IML_FLOAT32)IML2_DEFAULT_LATITUDE);
        if (gravityNormRes != IML_RESULT_SUCCESS || latitudeRes != IML_RESULT_SUCCESS) {
            /*
             * This binary has usable built-in location defaults but rejects
             * these optional setters on some builds.  The known-working
             * SpresenseDroidPractice bridge treats that as non-fatal.
             */
            LOGI("Using native default location: gravityNorm=%d latitude=%d", gravityNormRes, latitudeRes);
        }

        IML_CONDITION condition = {
            .gyrobiastime = 1.0F,
            .gyrocompasstime = 10.0F,
            .levelingtime = 1.0F,
            .gyrobiasth = 0.0002F,
            .levelingth = 0.001F
        };
        IML_RESULT conditionRes = Iml2_SetCondition(IML_INSTANCE, &condition);
        if (conditionRes != IML_RESULT_SUCCESS) {
            LOGE("Failed to set condition: %d", conditionRes);
            Iml2_Delete(IML_INSTANCE);
            g_isInitialized = JNI_FALSE;
            return JNI_FALSE;
        }

        IML_Vector3D set_velocity;
        IML_Vector3D set_position;

        set_velocity.x = 0.0;
        set_velocity.y = 0.0;
        set_velocity.z = 0.0;
        set_position.x = 0.0;
        set_position.y = 0.0;
        set_position.z = 0.0;

        IML_RESULT positionRes = Iml2_SetPosition(IML_INSTANCE, &set_position);
        IML_RESULT velocityRes = Iml2_SetVelocity(IML_INSTANCE, &set_velocity);
        if (positionRes != IML_RESULT_SUCCESS || velocityRes != IML_RESULT_SUCCESS) {
            /*
             * Position and velocity are irrelevant to Spright's attitude-only
             * use.  The reference bridge also leaves their default values when
             * these optional setters are unavailable.
             */
            LOGI("Using native initial position/velocity: position=%d velocity=%d", positionRes, velocityRes);
        }

        LOGI("InertialMotionLib2 initialized successfully with rate: %f", imuRate);
        g_initializeRefCount = 1;
        return JNI_TRUE;
    } else {
        LOGE("Failed to initialize InertialMotionLib2: %d", res);
        return JNI_FALSE;
    }
}

JNIEXPORT void JNICALL
Java_jp_co_sony_sprbox_imuadv_internal_InertialMotionLib_release(JNIEnv *env, jobject thiz) {
    if (g_isInitialized) {
        if (g_initializeRefCount > 1) {
            g_initializeRefCount--;
            LOGI("InertialMotionLib2 release deferred refCount: %d", g_initializeRefCount);
            return;
        }
        Iml2_Delete(IML_INSTANCE);
        g_isInitialized = JNI_FALSE;
        g_initializeRefCount = 0;
        g_getStatusFailureLogCount = 0;
        LOGI("InertialMotionLib2 released");
    }
}

JNIEXPORT jboolean JNICALL
Java_jp_co_sony_sprbox_imuadv_internal_InertialMotionLib_updateImu(JNIEnv *env, jobject thiz,
                                                             jfloatArray accel,
                                                             jfloatArray gyro,
                                                             jdouble timestamp,
                                                             jfloat temperature) {
    if (!g_isInitialized) {
        LOGE("Library not initialized");
        return JNI_FALSE;
    }

    // 配列の長さチェック
    jsize accelLen = (*env)->GetArrayLength(env, accel);
    jsize gyroLen = (*env)->GetArrayLength(env, gyro);

    if (accelLen < 3 || gyroLen < 3) {
        LOGE("Invalid array length: accel=%d, gyro=%d", accelLen, gyroLen);
        return JNI_FALSE;
    }

    jfloat *accelData = (*env)->GetFloatArrayElements(env, accel, NULL);
    jfloat *gyroData = (*env)->GetFloatArrayElements(env, gyro, NULL);
    if (accelData == NULL || gyroData == NULL) {
        LOGE("Failed to get IMU array elements");
        if (accelData != NULL) {
            (*env)->ReleaseFloatArrayElements(env, accel, accelData, JNI_ABORT);
        }
        if (gyroData != NULL) {
            (*env)->ReleaseFloatArrayElements(env, gyro, gyroData, JNI_ABORT);
        }
        return JNI_FALSE;
    }

    IML_IMU iml_imu;
    iml_imu.time = (IML_DOUBLE)timestamp;
    iml_imu.temp = (IML_FLOAT32)temperature;
    iml_imu.acc.x = (IML_FLOAT64)accelData[0];
    iml_imu.acc.y = (IML_FLOAT64)accelData[1];
    iml_imu.acc.z = (IML_FLOAT64)accelData[2];
    iml_imu.gyro.x = (IML_FLOAT64)gyroData[0];
    iml_imu.gyro.y = (IML_FLOAT64)gyroData[1];
    iml_imu.gyro.z = (IML_FLOAT64)gyroData[2];

    IML_RESULT res = Iml2_UpdateImu(IML_INSTANCE, &iml_imu);

    (*env)->ReleaseFloatArrayElements(env, accel, accelData, JNI_ABORT);
    (*env)->ReleaseFloatArrayElements(env, gyro, gyroData, JNI_ABORT);

    if (res != IML_RESULT_SUCCESS) {
        LOGE("Failed to update IMU data: %d", res);
    }

    return (res == IML_RESULT_SUCCESS) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_jp_co_sony_sprbox_imuadv_internal_InertialMotionLib_setMode(JNIEnv *env, jobject thiz, jint mode) {
    if (!g_isInitialized) {
        LOGE("Library not initialized");
        return JNI_FALSE;
    }

    IML_RESULT res = Iml2_SetMode(IML_INSTANCE, (IML_MODE)mode);
    if (res == IML_RESULT_DEFAULT && mode == IML_MODE_DEFAULT) {
        /*
         * The singleton is created in DEFAULT mode and reports DEFAULT rather
         * than SUCCESS when that same mode is applied again.
         */
        return JNI_TRUE;
    }
    if (res != IML_RESULT_SUCCESS) {
        LOGE("Failed to set mode %d: %d", mode, res);
    }
    return (res == IML_RESULT_SUCCESS) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jfloatArray JNICALL
Java_jp_co_sony_sprbox_imuadv_internal_InertialMotionLib_getEstimateSnapshot(JNIEnv *env, jobject thiz) {
    jfloatArray result = (*env)->NewFloatArray(env, 13);
    if (result == NULL) {
        LOGE("Failed to allocate memory for estimate snapshot array");
        return NULL;
    }

    if (!g_isInitialized) {
        LOGE("Library not initialized");
        return result;
    }

    IML_QUATERNION q;
    IML_EULERANGLE euler;
    IML_POSITION position;
    IML_VELOCITY velocity;
    IML_RESULT qRes = Iml2_GetQuaternion(IML_INSTANCE, &q);
    IML_RESULT eulerRes = Iml2_GetEulerAngle(IML_INSTANCE, &euler);
    IML_RESULT positionRes = Iml2_GetPosition(IML_INSTANCE, &position);
    IML_RESULT velocityRes = Iml2_GetVelocity(IML_INSTANCE, &velocity);

    if (qRes != IML_RESULT_SUCCESS || eulerRes != IML_RESULT_SUCCESS ||
        positionRes != IML_RESULT_SUCCESS || velocityRes != IML_RESULT_SUCCESS) {
        LOGE("Failed to get estimate snapshot: quat=%d euler=%d position=%d velocity=%d",
             qRes, eulerRes, positionRes, velocityRes);
        return result;
    }

    jfloat values[13];
    values[0] = (jfloat)q.quaternion.w;
    values[1] = (jfloat)q.quaternion.x;
    values[2] = (jfloat)q.quaternion.y;
    values[3] = (jfloat)q.quaternion.z;
    values[4] = (jfloat)euler.eulerangle.x;
    values[5] = (jfloat)euler.eulerangle.y;
    values[6] = (jfloat)euler.eulerangle.z;
    values[7] = (jfloat)position.position.x;
    values[8] = (jfloat)position.position.y;
    values[9] = (jfloat)position.position.z;
    values[10] = (jfloat)velocity.velocity.x;
    values[11] = (jfloat)velocity.velocity.y;
    values[12] = (jfloat)velocity.velocity.z;
    (*env)->SetFloatArrayRegion(env, result, 0, 13, values);
    return result;
}

JNIEXPORT jfloatArray JNICALL
Java_jp_co_sony_sprbox_imuadv_internal_InertialMotionLib_getQuaternion(JNIEnv *env, jobject thiz) {
    jfloatArray result = (*env)->NewFloatArray(env, 4);
    if (result == NULL) {
        LOGE("Failed to allocate memory for quaternion array");
        return NULL;
    }

    if (!g_isInitialized) {
        LOGE("Library not initialized");
        return result;
    }

    IML_QUATERNION q;
    IML_RESULT res = Iml2_GetQuaternion(IML_INSTANCE, &q);

    if (res == IML_RESULT_SUCCESS) {
        jfloat quaternion[4];
        quaternion[0] = (jfloat)q.quaternion.w;
        quaternion[1] = (jfloat)q.quaternion.x;
        quaternion[2] = (jfloat)q.quaternion.y;
        quaternion[3] = (jfloat)q.quaternion.z;

        (*env)->SetFloatArrayRegion(env, result, 0, 4, quaternion);
    } else {
        LOGE("Failed to get quaternion: %d", res);
    }

    return result;
}

JNIEXPORT jfloatArray JNICALL
Java_jp_co_sony_sprbox_imuadv_internal_InertialMotionLib_getEulerAngle(JNIEnv *env, jobject thiz) {
    jfloatArray result = (*env)->NewFloatArray(env, 3);
    if (result == NULL) {
        LOGE("Failed to allocate memory for euler angle array");
        return NULL;
    }

    if (!g_isInitialized) {
        LOGE("Library not initialized");
        return result;
    }

    IML_EULERANGLE euler;
    IML_RESULT res = Iml2_GetEulerAngle(IML_INSTANCE, &euler);

    if (res == IML_RESULT_SUCCESS) {
        jfloat angles[3];
        angles[0] = (jfloat)euler.eulerangle.x;
        angles[1] = (jfloat)euler.eulerangle.y;
        angles[2] = (jfloat)euler.eulerangle.z;

        (*env)->SetFloatArrayRegion(env, result, 0, 3, angles);
    } else {
        LOGE("Failed to get euler angles: %d", res);
    }

    return result;
}

JNIEXPORT jfloatArray JNICALL
Java_jp_co_sony_sprbox_imuadv_internal_InertialMotionLib_getPosition(JNIEnv *env, jobject thiz) {
    jfloatArray result = (*env)->NewFloatArray(env, 3);
    if (result == NULL) {
        LOGE("Failed to allocate memory for position array");
        return NULL;
    }

    if (!g_isInitialized) {
        LOGE("Library not initialized");
        return result;
    }

    IML_POSITION position;
    IML_RESULT res = Iml2_GetPosition(IML_INSTANCE, &position);

    if (res == IML_RESULT_SUCCESS) {
        jfloat pos[3];
        pos[0] = (jfloat)position.position.x;
        pos[1] = (jfloat)position.position.y;
        pos[2] = (jfloat)position.position.z;

        (*env)->SetFloatArrayRegion(env, result, 0, 3, pos);
    } else {
        LOGE("Failed to get position: %d", res);
    }

    return result;
}

JNIEXPORT jfloatArray JNICALL
Java_jp_co_sony_sprbox_imuadv_internal_InertialMotionLib_getVelocity(JNIEnv *env, jobject thiz) {
    jfloatArray result = (*env)->NewFloatArray(env, 3);
    if (result == NULL) {
        LOGE("Failed to allocate memory for velocity array");
        return NULL;
    }

    if (!g_isInitialized) {
        LOGE("Library not initialized");
        return result;
    }

    IML_VELOCITY velocity;
    IML_RESULT res = Iml2_GetVelocity(IML_INSTANCE, &velocity);

    if (res == IML_RESULT_SUCCESS) {
        jfloat vel[3];
        vel[0] = (jfloat)velocity.velocity.x;
        vel[1] = (jfloat)velocity.velocity.y;
        vel[2] = (jfloat)velocity.velocity.z;

        (*env)->SetFloatArrayRegion(env, result, 0, 3, vel);
    } else {
        LOGE("Failed to get velocity: %d", res);
    }

    return result;
}

JNIEXPORT jint JNICALL
Java_jp_co_sony_sprbox_imuadv_internal_InertialMotionLib_getStatus(JNIEnv *env, jobject thiz) {
    if (!g_isInitialized) {
        LOGE("Library not initialized");
        return -1;
    }

    IML_STATE state;
    IML_RESULT res = Iml2_GetStatus(IML_INSTANCE, &state);

    if (res == IML_RESULT_SUCCESS) {
        g_getStatusFailureLogCount = 0;
        return (jint)state;
    } else {
        g_getStatusFailureLogCount++;
        if (g_getStatusFailureLogCount <= 3 || g_getStatusFailureLogCount % 60 == 0) {
            LOGE("Failed to get status: %d count=%d", res, g_getStatusFailureLogCount);
        }
        return -1;
    }
}

JNIEXPORT jboolean JNICALL
Java_jp_co_sony_sprbox_imuadv_internal_InertialMotionLib_startCalib(JNIEnv *env, jobject thiz, jint kind) {
    if (!g_isInitialized) {
        LOGE("Library not initialized");
        return JNI_FALSE;
    }

    IML_RESULT res = Iml2_StartCalib(IML_INSTANCE, (IML_UINT16)kind);
    return (res == IML_RESULT_SUCCESS) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jobject JNICALL
Java_jp_co_sony_sprbox_imuadv_internal_InertialMotionLib_finishCalib(JNIEnv *env, jobject thiz) {
    // CalibResultクラスのインスタンスを作成
    jclass calibResultClass = (*env)->FindClass(env, "jp/co/sony/sprbox/imuadv/internal/CalibResult");
    if (calibResultClass == NULL) {
        LOGE("Failed to find CalibResult class");
        return NULL;
    }

    jmethodID constructor = (*env)->GetMethodID(env, calibResultClass, "<init>", "()V");
    if (constructor == NULL) {
        LOGE("Failed to find CalibResult constructor");
        return NULL;
    }

    if (!g_isInitialized) {
        LOGE("Library not initialized");
        return NULL;
    }

    jobject resultObj = (*env)->NewObject(env, calibResultClass, constructor);
    if (resultObj == NULL) {
        LOGE("Failed to create CalibResult object");
        return NULL;
    }

    IML_CALIBRESULT calibResult = {0};
    IML_RESULT res = Iml2_FinishCalib(IML_INSTANCE, &calibResult);

    if (res != IML_RESULT_SUCCESS) {
        LOGE("Failed to finish calibration: %d", res);
        return NULL;
    }

    // フィールドにデータをセット
    jfieldID fieldId;

    // gyrobias情報
    fieldId = (*env)->GetFieldID(env, calibResultClass, "gyroBiasCalibLevel", "I");
    if (fieldId == NULL) return NULL;
    (*env)->SetIntField(env, resultObj, fieldId, calibResult.gyrobias.caliblv);

    fieldId = (*env)->GetFieldID(env, calibResultClass, "gyroBiasError", "F");
    if (fieldId == NULL) return NULL;
    (*env)->SetFloatField(env, resultObj, fieldId, calibResult.gyrobias.err);

    // leveling情報
    fieldId = (*env)->GetFieldID(env, calibResultClass, "levelingCalibLevel", "I");
    if (fieldId == NULL) return NULL;
    (*env)->SetIntField(env, resultObj, fieldId, calibResult.leveling.caliblv);

    fieldId = (*env)->GetFieldID(env, calibResultClass, "levelingError", "F");
    if (fieldId == NULL) return NULL;
    (*env)->SetFloatField(env, resultObj, fieldId, calibResult.leveling.err);

    return resultObj;
}

JNIEXPORT jboolean JNICALL
Java_jp_co_sony_sprbox_imuadv_internal_InertialMotionLib_startUpdatePositionAttitude(JNIEnv *env, jobject thiz) {
    if (!g_isInitialized) {
        LOGE("Library not initialized");
        return JNI_FALSE;
    }

    IML_RESULT res = Iml2_StartUpdatePositionAttitude(IML_INSTANCE);
    if (res != IML_RESULT_SUCCESS) {
        LOGE("Failed to start position/attitude update: %d", res);
    }
    return (res == IML_RESULT_SUCCESS) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_jp_co_sony_sprbox_imuadv_internal_InertialMotionLib_finishUpdatePositionAttitude(JNIEnv *env, jobject thiz) {
    if (!g_isInitialized) {
        LOGE("Library not initialized");
        return JNI_FALSE;
    }

    IML_RESULT res = Iml2_FinishUpdatePositionAttitude(IML_INSTANCE);
    return (res == IML_RESULT_SUCCESS) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_jp_co_sony_sprbox_imuadv_internal_InertialMotionLib_setQuaternion(JNIEnv *env, jobject thiz, jfloatArray quaternion) {
    if (!g_isInitialized) {
        LOGE("Library not initialized");
        return JNI_FALSE;
    }

    jsize len = (*env)->GetArrayLength(env, quaternion);
    if (len < 4) {
        LOGE("Invalid quaternion array length: %d", len);
        return JNI_FALSE;
    }

    jfloat *qData = (*env)->GetFloatArrayElements(env, quaternion, NULL);
    if (qData == NULL) {
        LOGE("Failed to get quaternion array elements");
        return JNI_FALSE;
    }

    IML_Vector4D q;
    q.w = qData[0];
    q.x = qData[1];
    q.y = qData[2];
    q.z = qData[3];

    IML_RESULT res = Iml2_SetQuaternion(IML_INSTANCE, &q);

    (*env)->ReleaseFloatArrayElements(env, quaternion, qData, JNI_ABORT);

    return (res == IML_RESULT_SUCCESS) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_jp_co_sony_sprbox_imuadv_internal_InertialMotionLib_setVelocity(JNIEnv *env, jobject thiz, jfloatArray velocity) {
    if (!g_isInitialized) {
        LOGE("Library not initialized");
        return JNI_FALSE;
    }

    jsize len = (*env)->GetArrayLength(env, velocity);
    if (len < 3) {
        LOGE("Invalid velocity array length: %d", len);
        return JNI_FALSE;
    }

    jfloat *vData = (*env)->GetFloatArrayElements(env, velocity, NULL);
    if (vData == NULL) {
        LOGE("Failed to get velocity array elements");
        return JNI_FALSE;
    }

    IML_Vector3D v;
    v.x = vData[0];
    v.y = vData[1];
    v.z = vData[2];

    IML_RESULT res = Iml2_SetVelocity(IML_INSTANCE, &v);

    (*env)->ReleaseFloatArrayElements(env, velocity, vData, JNI_ABORT);

    return (res == IML_RESULT_SUCCESS) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_jp_co_sony_sprbox_imuadv_internal_InertialMotionLib_setPosition(JNIEnv *env, jobject thiz, jfloatArray position) {
    if (!g_isInitialized) {
        LOGE("Library not initialized");
        return JNI_FALSE;
    }

    jsize len = (*env)->GetArrayLength(env, position);
    if (len < 3) {
        LOGE("Invalid position array length: %d", len);
        return JNI_FALSE;
    }

    jfloat *pData = (*env)->GetFloatArrayElements(env, position, NULL);
    if (pData == NULL) {
        LOGE("Failed to get position array elements");
        return JNI_FALSE;
    }

    IML_Vector3D p;
    p.x = pData[0];
    p.y = pData[1];
    p.z = pData[2];

    IML_RESULT res = Iml2_SetPosition(IML_INSTANCE, &p);

    (*env)->ReleaseFloatArrayElements(env, position, pData, JNI_ABORT);

    return (res == IML_RESULT_SUCCESS) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_jp_co_sony_sprbox_imuadv_internal_InertialMotionLib_setGravityNorm(JNIEnv *env, jobject thiz, jdouble gravityNorm) {
    if (!g_isInitialized) {
        LOGE("Library not initialized");
        return JNI_FALSE;
    }

    IML_RESULT res = Iml2_SetGravityNorm(IML_INSTANCE, (IML_FLOAT64)gravityNorm);
    return (res == IML_RESULT_SUCCESS) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_jp_co_sony_sprbox_imuadv_internal_InertialMotionLib_setGravityAttitude(JNIEnv *env, jobject thiz, jfloatArray gravity) {
    if (!g_isInitialized) {
        LOGE("Library not initialized");
        return JNI_FALSE;
    }

    jsize len = (*env)->GetArrayLength(env, gravity);
    if (len < 3) {
        LOGE("Invalid gravity array length: %d", len);
        return JNI_FALSE;
    }

    jfloat *gData = (*env)->GetFloatArrayElements(env, gravity, NULL);
    if (gData == NULL) {
        LOGE("Failed to get gravity array elements");
        return JNI_FALSE;
    }

    IML_Vector3D g;
    g.x = gData[0];
    g.y = gData[1];
    g.z = gData[2];

    IML_RESULT res = Iml2_SetGravityAttitude(IML_INSTANCE, &g);

    (*env)->ReleaseFloatArrayElements(env, gravity, gData, JNI_ABORT);

    return (res == IML_RESULT_SUCCESS) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_jp_co_sony_sprbox_imuadv_internal_InertialMotionLib_setCalibParam(JNIEnv *env, jobject thiz, jfloatArray param, jint kind) {
    if (!g_isInitialized) {
        LOGE("Library not initialized");
        return JNI_FALSE;
    }

    jsize len = (*env)->GetArrayLength(env, param);
    if (len < 3) {
        LOGE("Invalid calib param array length: %d", len);
        return JNI_FALSE;
    }

    jfloat *pData = (*env)->GetFloatArrayElements(env, param, NULL);
    if (pData == NULL) {
        LOGE("Failed to get calib param array elements");
        return JNI_FALSE;
    }

    IML_Vector3D p;
    p.x = pData[0];
    p.y = pData[1];
    p.z = pData[2];

    IML_RESULT res = Iml2_SetCalibParam(IML_INSTANCE, &p, (IML_CALIBPARAMKIND)kind);

    (*env)->ReleaseFloatArrayElements(env, param, pData, JNI_ABORT);

    return (res == IML_RESULT_SUCCESS) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jobject JNICALL
Java_jp_co_sony_sprbox_imuadv_internal_InertialMotionLib_getCalibParam(JNIEnv *env, jobject thiz, jint kind) {
    // CalibParamResultクラスのインスタンスを作成
    jclass calibParamClass = (*env)->FindClass(env, "jp/co/sony/sprbox/imuadv/internal/CalibParamResult");
    if (calibParamClass == NULL) {
        LOGE("Failed to find CalibParamResult class");
        return NULL;
    }

    jmethodID constructor = (*env)->GetMethodID(env, calibParamClass, "<init>", "()V");
    if (constructor == NULL) {
        LOGE("Failed to find CalibParamResult constructor");
        return NULL;
    }

    if (!g_isInitialized) {
        LOGE("Library not initialized");
        return NULL;
    }

    IML_Vector3D param = {0};
    IML_FLOAT32 err = 0.0F;
    IML_RESULT res = Iml2_GetCalibParam(IML_INSTANCE, &param, &err, (IML_CALIBPARAMKIND)kind);

    if (res != IML_RESULT_SUCCESS) {
        LOGE("Failed to get calib param: %d", res);
        return NULL;
    }

    jobject resultObj = (*env)->NewObject(env, calibParamClass, constructor);
    if (resultObj == NULL) {
        LOGE("Failed to create CalibParamResult object");
        return NULL;
    }

    // パラメータ配列を作成
    jfloatArray paramArray = (*env)->NewFloatArray(env, 3);
    if (paramArray == NULL) return NULL;

    jfloat values[3] = {(jfloat)param.x, (jfloat)param.y, (jfloat)param.z};
    (*env)->SetFloatArrayRegion(env, paramArray, 0, 3, values);

    // フィールドにデータをセット
    jfieldID fieldId;

    fieldId = (*env)->GetFieldID(env, calibParamClass, "param", "[F");
    if (fieldId == NULL) {
        LOGE("Failed to find CalibParamResult.param field");
        return NULL;
    }
    (*env)->SetObjectField(env, resultObj, fieldId, paramArray);

    fieldId = (*env)->GetFieldID(env, calibParamClass, "error", "F");
    if (fieldId == NULL) {
        LOGE("Failed to find CalibParamResult.error field");
        return NULL;
    }
    (*env)->SetFloatField(env, resultObj, fieldId, err);

    return resultObj;
}
