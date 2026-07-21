#ifndef IML_COMMON_H
#define IML_COMMON_H

/*
 * Keep these ABI aliases identical to the headers used to build the bundled
 * prebuilt libinertialmotionlib2.so.  In particular, IML_FLOAT64 is a float
 * despite its historical name.  Changing it to double changes every vector,
 * IMU, quaternion and Euler struct layout passed across the binary boundary.
 */
#define IML_FLOAT32 float
#define IML_FLOAT64 float
#define IML_DOUBLE double
#define IML_INT8 char
#define IML_UINT8 unsigned char
#define IML_INT16 short
#define IML_UINT16 unsigned short
#define IML_INT32 long
#define IML_UINT32 unsigned long
#define IML_INT64 long long
#define IML_UINT64 unsigned long long
#define IML_INT int
#define IML_UINT unsigned int

typedef enum
{
	IML_RESULT_DEFAULT = 0,
	IML_RESULT_SUCCESS,
	IML_RESULT_FAIL_INVALIDVALUE,
	IML_RESULT_FAIL_INVALIDSTATE,
}IML_RESULT;

typedef enum
{
	IML_STATE_DEFAULT = 0,
	IML_STATE_STOP,
	IML_STATE_MOVE,
}IML_STATE;

typedef enum{
	IML_GYROCOMPASS_STAT_NOTCALIBED = 0,
	IML_GYROCOMPASS_STAT_1ATTCALIBED,		// measured at 1 attitude from NOTCALIBED
	IML_GYROCOMPASS_STAT_FINISHCALIBED,		// finish calibration from 1ATTCALIBED
}IML_GYROCOMPASS_STAT;

typedef enum{
	IML_MODE_DEFAULT = 1,
	IML_MODE_NHC = 3, // side and up velcity forced to 0
}IML_MODE;

typedef enum{
	IML_CALIBPARAMKIND_ACCBIAS = 0,
	IML_CALIBPARAMKIND_GYROBIAS,
}IML_CALIBPARAMKIND;

typedef struct
{
  IML_FLOAT64 x;
  IML_FLOAT64 y;
  IML_FLOAT64 z;
  IML_FLOAT64 w;
}IML_Vector4D;

typedef struct
{
  IML_FLOAT64 x;
  IML_FLOAT64 y;
  IML_FLOAT64 z;
}IML_Vector3D;


typedef struct
{
	IML_FLOAT32 gyrobiastime;		//　unit: sec.
	IML_FLOAT32 levelingtime;		//　unit: sec.
	IML_FLOAT32 gyrocompasstime;	//　unit: sec.
	IML_FLOAT32 gyrobiasth;			//	unit: rad/s
	IML_FLOAT32 levelingth;			//	unit: m/s/s
}IML_CONDITION;

typedef struct
{
	IML_DOUBLE time;	// unit: sec.
	IML_FLOAT32 temp;	// unit: degC
	IML_Vector3D acc;	// unit: m/s/s.
	IML_Vector3D gyro;	// unit: rad/s.
}IML_IMU;

typedef struct
{
	IML_DOUBLE time;			// unit: sec.
	IML_Vector4D quaternion;	// unit: non.
}IML_QUATERNION;

typedef struct
{
	IML_DOUBLE time;			// unit: sec.
	IML_Vector3D eulerangle;	// unit: rad.
}IML_EULERANGLE;

typedef struct
{
	IML_DOUBLE time;		// unit: sec.
	IML_Vector3D velocity;	// unit: m/s.
}IML_VELOCITY;

typedef struct
{
	IML_DOUBLE time;		// unit: sec.
	IML_Vector3D position;	// unit: m.
}IML_POSITION;

typedef enum{
	IML_CALIBKIND_GYROBIAS = 0x01,
	IML_CALIBKIND_LEVELING = 0x02,
	IML_CALIBKIND_GYROCOMPASS = 0x04,
	IML_CALIBKIND_ZUPT = 0x08,
}IML_CALIBKIND;

typedef enum{
	IML_CALIBRESULT_CALBLV_NOTAVAILABLE = -1,
	IML_CALIBRESULT_CALBLV_NOTCALIBED,
	IML_CALIBRESULT_CALBLV_LOW,
	IML_CALIBRESULT_CALBLV_MIDDLE,
	IML_CALIBRESULT_CALBLV_HIGH,
}IML_CALIBRESULT_CALBLV;

typedef struct{
	IML_CALIBRESULT_CALBLV caliblv;	// calib index.
	IML_FLOAT32 err;				// error index
}IML_CALIBRESULT_DATA;


typedef struct{
	IML_CALIBRESULT_DATA gyrobias;
	IML_CALIBRESULT_DATA leveling;
}IML_CALIBRESULT;


#endif // #ifndef IML_COMMON_H
