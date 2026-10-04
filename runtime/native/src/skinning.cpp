#include "skinning.h"
#include <algorithm>
#include <atomic>
#include <cmath>
#include <condition_variable>
#include <cstring>
#include <mutex>
#include <thread>
#include <vector>

namespace {
struct V {float x,y,z;};
V add(V a,V b){return {a.x+b.x,a.y+b.y,a.z+b.z};}
V sub(V a,V b){return {a.x-b.x,a.y-b.y,a.z-b.z};}
V mul(V a,float b){return {a.x*b,a.y*b,a.z*b};}
float dot(V a,V b){return a.x*b.x+a.y*b.y+a.z*b.z;}
V cross(V a,V b){return {a.y*b.z-a.z*b.y,a.z*b.x-a.x*b.z,a.x*b.y-a.y*b.x};}
V read(const float *p){return {p[0],p[1],p[2]};}
void write(float *p,V v){p[0]=v.x;p[1]=v.y;p[2]=v.z;}
V unit(V v){double n=std::sqrt(double(v.x)*v.x+double(v.y)*v.y+double(v.z)*v.z);return n<1e-30?V{0,0,0}:mul(v,float(1/n));}
V direction(const float *m,V v){return {m[0]*v.x+m[4]*v.y+m[8]*v.z,m[1]*v.x+m[5]*v.y+m[9]*v.z,m[2]*v.x+m[6]*v.y+m[10]*v.z};}
V point(const float *m,V v){V p=direction(m,v);return {p.x+m[12],p.y+m[13],p.z+m[14]};}
float determinant(const float *m){return dot(read(m),cross(read(m+4),read(m+8)));}
struct Q {double v[4];};
double dot(Q a,Q b){double s=0;for(int i=0;i<4;i++)s+=a.v[i]*b.v[i];return s;}
Q scale(Q a,double s){for(auto &x:a.v)x*=s;return a;}
Q add(Q a,Q b){for(int i=0;i<4;i++)a.v[i]+=b.v[i];return a;}
Q multiply(Q a,Q b){const double *x=a.v,*y=b.v;return {{x[3]*y[0]+x[0]*y[3]+x[1]*y[2]-x[2]*y[1],x[3]*y[1]-x[0]*y[2]+x[1]*y[3]+x[2]*y[0],x[3]*y[2]+x[0]*y[1]-x[1]*y[0]+x[2]*y[3],x[3]*y[3]-x[0]*y[0]-x[1]*y[1]-x[2]*y[2]}};}
bool rotation(const float *m,Q &q){
  for(int c=0;c<3;c++)for(int d=0;d<3;d++){double p=0;for(int r=0;r<3;r++)p+=double(m[c*4+r])*m[d*4+r];if(std::abs(p-(c==d?1:0))>.002)return false;}
  if(determinant(m)<0)return false;
  double trace=m[0]+m[5]+m[10];
  if(trace>0){double s=std::sqrt(trace+1)*2;q={{(m[6]-m[9])/s,(m[8]-m[2])/s,(m[1]-m[4])/s,s/4}};}
  else {int a=m[0]>m[5]?0:1;if(m[10]>m[a*4+a])a=2;int b=(a+1)%3,c=(a+2)%3;double s=std::sqrt(1+m[a*4+a]-m[b*4+b]-m[c*4+c])*2;
    q={{0,0,0,0}};q.v[a]=s/4;q.v[b]=(m[a*4+b]+m[b*4+a])/s;q.v[c]=(m[a*4+c]+m[c*4+a])/s;q.v[3]=(m[b*4+c]-m[c*4+b])/s;}
  q=scale(q,1/std::sqrt(dot(q,q)));return true;
}
void matrix(Q q,V t,float *m){
  float x=float(q.v[0]),y=float(q.v[1]),z=float(q.v[2]),w=float(q.v[3]);
  double n=std::sqrt(double(x)*x+double(y)*y+double(z)*z+double(w)*w);x=float(x/n);y=float(y/n);z=float(z/n);w=float(w/n);
  float a[16]={1-2*y*y-2*z*z,2*x*y+2*z*w,2*x*z-2*y*w,0,2*x*y-2*z*w,1-2*x*x-2*z*z,2*y*z+2*x*w,0,2*x*z+2*y*w,2*y*z-2*x*w,1-2*x*x-2*y*y,0,t.x,t.y,t.z,1};std::copy(a,a+16,m);
}
Q slerp(Q a,Q b,double t){double d=dot(a,b);if(d<0){b=scale(b,-1);d=-d;}double x=1-t,y=t;if(d<.9995){double angle=std::acos(std::min(1.,d)),s=std::sin(angle);x=std::sin((1-t)*angle)/s;y=std::sin(t*angle)/s;}Q q=add(scale(a,x),scale(b,y));return scale(q,1/std::sqrt(dot(q,q)));}
const float identity[16]={1,0,0,0,0,1,0,0,0,0,1,0,0,0,0,1};

// Bounded persistent workers; independent callers serialize dispatch, never create per-frame threads.
class Workers {
  std::mutex dispatch,mutex;std::condition_variable ready,finished;std::vector<std::thread> threads;
  bool stop=false;unsigned epoch=0;int pending=0,count=0;void (*function)(void*,int,int)=nullptr;void *context=nullptr;
public:
  Workers(){unsigned n=std::max(1u,std::min(4u,std::thread::hardware_concurrency()));
    try{for(unsigned i=1;i<n;i++)threads.emplace_back([this,i,n]{unsigned seen=0;for(;;){std::unique_lock lock(mutex);ready.wait(lock,[&]{return stop||epoch!=seen;});if(stop)return;seen=epoch;auto fn=function;auto ctx=context;int size=count;lock.unlock();fn(ctx,int(size*i/n),int(size*(i+1)/n));lock.lock();if(--pending==0)finished.notify_one();}});}
    catch(...){ {std::lock_guard lock(mutex);stop=true;}ready.notify_all();for(auto &t:threads)t.join();throw;}}
  ~Workers(){{std::lock_guard lock(mutex);stop=true;}ready.notify_all();for(auto &t:threads)t.join();}
  void run(int n,void (*fn)(void*,int,int),void *ctx){std::lock_guard serialize(dispatch);unsigned workers=unsigned(threads.size()+1);
    {std::lock_guard lock(mutex);count=n;function=fn;context=ctx;pending=int(threads.size());epoch++;}ready.notify_all();fn(ctx,0,int(n/workers));std::unique_lock lock(mutex);finished.wait(lock,[&]{return pending==0;});}
};
struct Job {const int32_t *ranges,*joints,*modes;const float *weights,*sdef,*palette,*input;float *out;const Q *rotations;int flags;std::atomic<bool> valid{true};};
void vertices(void *context,int first,int last){auto &j=*static_cast<Job*>(context);
  for(int v=first;v<last;v++){
    int a=j.ranges[v],b=j.ranges[v+1];float m[16]={};const float *in=j.input+v*10;float *out=j.out+v*10;V position;
    if(j.modes[v]==1){int i=j.joints[a],k=j.joints[a+1];const float *m0=i<0?identity:j.palette+i*16,*m1=k<0?identity:j.palette+k*16;Q q=slerp(i<0?Q{{0,0,0,1}}:j.rotations[i],k<0?Q{{0,0,0,1}}:j.rotations[k],j.weights[a+1]);
      V c=read(j.sdef+v*9),r0=read(j.sdef+v*9+3),r1=read(j.sdef+v*9+6),rw=add(mul(r0,j.weights[a]),mul(r1,j.weights[a+1]));
      matrix(q,{0,0,0},m);position=add(add(direction(m,sub(read(in),c)),mul(point(m0,add(c,mul(sub(r0,rw),.5f))),j.weights[a])),mul(point(m1,add(c,mul(sub(r1,rw),.5f))),j.weights[a+1]));
    }else if(j.modes[v]==2){Q real{{0,0,0,0}},dual{{0,0,0,0}},reference{{0,0,0,0}};bool found=false;
      for(int i=a;i<b;i++){double weight=j.weights[i];if(weight==0)continue;int bone=j.joints[i];Q q=j.rotations[bone],d=multiply(Q{{j.palette[bone*16+12],j.palette[bone*16+13],j.palette[bone*16+14],0}},q);if(!found){reference=q;found=true;}if(dot(reference,q)<0)weight=-weight;real=add(real,scale(q,weight));dual=add(dual,scale(d,.5*weight));}
      double n=std::sqrt(dot(real,real));if(n<1e-12){j.valid=false;continue;}real=scale(real,1/n);dual=scale(dual,1/n);dual=add(dual,scale(real,-dot(real,dual)));Q t=multiply(dual,Q{{-real.v[0],-real.v[1],-real.v[2],real.v[3]}});matrix(real,{float(2*t.v[0]),float(2*t.v[1]),float(2*t.v[2])},m);position=point(m,read(in));
    }else{for(int i=a;i<b;i++){float weight=j.weights[i];if(weight==0)continue;auto p=j.palette+j.joints[i]*16;for(int c=0;c<4;c++)for(int r=0;r<3;r++)m[c*4+r]+=weight*p[c*4+r];}m[15]=1;position=point(m,read(in));}
    write(out,position);V normal{0,0,0};
    if(j.flags&1){normal=read(in+3);if(j.flags&4){normal=add(add(mul(cross(read(m+4),read(m+8)),normal.x),mul(cross(read(m+8),read(m)),normal.y)),mul(cross(read(m),read(m+4)),normal.z));if(determinant(m)<0)normal=mul(normal,-1);}else normal=direction(m,normal);normal=unit(normal);}write(out+3,normal);
    if(j.flags&2){V tangent=direction(m,read(in+6));if(j.flags&1)tangent=sub(tangent,mul(normal,dot(normal,tangent)));write(out+6,unit(tangent));out[9]=determinant(m)<0?-in[9]:in[9];}else {write(out+6,{0,0,0});out[9]=0;}
    for(int i=0;i<10;i++)if(!std::isfinite(out[i]))j.valid=false;
  }
}
bool finite(const float *p,int n){for(int i=0;i<n;i++)if(!std::isfinite(p[i]))return false;return true;}
}
extern "C" int ysm_skin_abi(void){return 1;}
extern "C" int ysm_skin(const int32_t *ranges,int rn,const int32_t *joints,int jn,const float *weights,int wn,const int32_t *modes,int n,const float *sdef,int sn,const float *palette,int pn,const float *input,int in,float *output,int on,int flags,int parallel){
  try {
    if(n<0||n>2000000||jn<0||jn>8000000||rn!=n+1||wn!=jn||pn<0||pn%16||pn/16>65536||in!=n*10||on<n*10||flags<0||flags>7||parallel<0||parallel>1)return -1;
    if(!ranges||(jn&&(!joints||!weights))||(n&&(!modes||!input||!output))||(pn&&!palette)||(sn&&!sdef))return -1;
    if(ranges[0]!=0||ranges[n]!=jn||!finite(weights,wn)||!finite(palette,pn)||!finite(input,in)||sn<0||(sn!=0&&sn!=n*9)||!finite(sdef,sn))return -1;
    int bones=pn/16;std::vector<Q> rotations(bones);std::vector<unsigned char> rigid(bones,0);
    for(int b=0;b<bones;b++){const float *m=palette+b*16;if(m[3]!=0||m[7]!=0||m[11]!=0||m[15]!=1)return -1;}
    for(int v=0;v<n;v++){int a=ranges[v],b=ranges[v+1];if(a<0||b<a||b>jn||modes[v]<0||modes[v]>2)return -1;double sum=0;
      if(modes[v]==1&&(b-a!=2||sn!=n*9))return -1;if(modes[v]==2&&b-a!=4)return -1;
      for(int i=a;i<b;i++){int bone=joints[i];float w=weights[i];if(w<0||bone< -1||(w!=0&&(bone<0||bone>=bones)))return -1;sum+=w;
        // SDEF validates both endpoints, including an endpoint with zero weight.
        if(modes[v]!=0&&bone>=0&&(w!=0||modes[v]==1)){if(bone>=bones)return -1;if(!rigid[bone]){if(!rotation(palette+bone*16,rotations[bone]))return -1;rigid[bone]=1;}}}
      if(sum<1e-12||(modes[v]==1&&std::abs(sum-1)>.0001))return -1;
    }
    thread_local std::vector<float> scratch;scratch.resize(n*10);Job job{ranges,joints,modes,weights,sdef,palette,input,scratch.data(),rotations.data(),flags};
    if(parallel&&n>=4096){static Workers workers;workers.run(n,vertices,&job);}else vertices(&job,0,n);
    if(!job.valid)return -2;if(n)std::memcpy(output,scratch.data(),size_t(n)*10*sizeof(float));return 0;
  }catch(...){return -2;}
}
