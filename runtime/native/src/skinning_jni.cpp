#include <jni.h>
#include "skinning.h"
#include <climits>
#include <cstdint>
namespace {
template<class T> T *buffer(JNIEnv *e,jobject b,int &length){if(!b){length=-1;return nullptr;}auto n=e->GetDirectBufferCapacity(b);void *p=e->GetDirectBufferAddress(b);if(n<0||n>INT_MAX||(p&&reinterpret_cast<uintptr_t>(p)%alignof(T))){length=-1;return nullptr;}length=int(n);return static_cast<T*>(p);}
}
extern "C" JNIEXPORT jint JNICALL Java_cc_sirrus_ysmlib_scene_natives_NativeDeformationProvider_nAbi(JNIEnv*,jclass){return ysm_skin_abi();}
extern "C" JNIEXPORT jint JNICALL Java_cc_sirrus_ysmlib_scene_natives_NativeDeformationProvider_nSkin(JNIEnv *e,jclass,jobject r,jobject j,jobject w,jobject m,jobject s,jobject p,jobject in,jobject out,jint flags,jboolean parallel){
  int rn,jn,wn,mn,sn,pn,inn,on;auto rp=buffer<int32_t>(e,r,rn);auto jp=buffer<int32_t>(e,j,jn);auto wp=buffer<float>(e,w,wn);auto mp=buffer<int32_t>(e,m,mn);auto sp=buffer<float>(e,s,sn);auto pp=buffer<float>(e,p,pn);auto ip=buffer<float>(e,in,inn);auto op=buffer<float>(e,out,on);
  return ysm_skin(rp,rn,jp,jn,wp,wn,mp,mn,sp,sn,pp,pn,ip,inn,op,on,flags,parallel?1:0);
}
