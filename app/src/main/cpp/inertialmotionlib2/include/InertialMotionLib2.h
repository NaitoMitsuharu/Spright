#ifndef INERTIAL_MOTION_LIB2_H
#define INERTIAL_MOTION_LIB2_H

#include "IML_Common.h"

struct imlinterface_impl;

typedef struct _IML_INTERFACE
{
	struct imlinterface_impl* p_impl;
}IML_INTERFACE;

#ifdef __cplusplus
extern "C" {
#endif
	IML_RESULT Iml2_Create(IML_INTERFACE* pThis, const IML_FLOAT32 imurate );
	IML_RESULT Iml2_Delete(IML_INTERFACE* pThis );

	IML_RESULT Iml2_SetMode(IML_INTERFACE* pThis, const IML_MODE mode );
	IML_RESULT Iml2_SetCondition(IML_INTERFACE* pThis, IML_CONDITION* const condition );
	IML_RESULT Iml2_SetCalibParam(IML_INTERFACE* pThis, IML_Vector3D* const param, const IML_CALIBPARAMKIND kind );
	IML_RESULT Iml2_SetGravityAttitude(IML_INTERFACE* pThis, IML_Vector3D* const gravity );
	IML_RESULT Iml2_SetGravityNorm(IML_INTERFACE* pThis, const IML_FLOAT64 gravitynorm );
	IML_RESULT Iml2_SetAzimuth(IML_INTERFACE* pThis, const IML_FLOAT32 azimuth );
	IML_RESULT Iml2_SetLatitude(IML_INTERFACE* pThis, const IML_FLOAT32 lat );
	IML_RESULT Iml2_SetQuaternion(IML_INTERFACE* pThis, IML_Vector4D* const q );
	IML_RESULT Iml2_SetVelocity(IML_INTERFACE* pThis, IML_Vector3D* const velo );
	IML_RESULT Iml2_SetPosition(IML_INTERFACE* pThis, IML_Vector3D* const position );

	IML_RESULT Iml2_UpdateImu(IML_INTERFACE* pThis, IML_IMU* const imu );

	IML_RESULT Iml2_GetMode(IML_INTERFACE* const pThis, IML_MODE* mode );
	IML_RESULT Iml2_GetCondition(IML_INTERFACE* const pThis, IML_CONDITION* condition );
	IML_RESULT Iml2_GetCalibParam(IML_INTERFACE* const pThis, IML_Vector3D* param, IML_FLOAT32* err, const IML_CALIBPARAMKIND kind );
	IML_RESULT Iml2_GetQuaternion(IML_INTERFACE* const pThis, IML_QUATERNION* q );
	IML_RESULT Iml2_GetEulerAngle(IML_INTERFACE* const pThis, IML_EULERANGLE* euler );
	IML_RESULT Iml2_GetVelocity(IML_INTERFACE* const pThis, IML_VELOCITY* velocity );
	IML_RESULT Iml2_GetPosition(IML_INTERFACE* const pThis, IML_POSITION* position );
	IML_RESULT Iml2_GetStatus(IML_INTERFACE* const pThis, IML_STATE* stat );

	IML_RESULT Iml2_GetCalibInProgressResult(IML_INTERFACE* pThis, IML_CALIBRESULT* result);
	IML_RESULT Iml2_StartCalib(IML_INTERFACE* pThis, IML_UINT16 const kind);
	IML_RESULT Iml2_FinishCalib(IML_INTERFACE* pThis, IML_CALIBRESULT* result);
	IML_RESULT Iml2_StartUpdatePositionAttitude(IML_INTERFACE* pThis );
	IML_RESULT Iml2_FinishUpdatePositionAttitude(IML_INTERFACE* pThis );

#ifdef __cplusplus
};
#endif
#endif // #ifndef INERTIAL_MOTION_LIB2_H
