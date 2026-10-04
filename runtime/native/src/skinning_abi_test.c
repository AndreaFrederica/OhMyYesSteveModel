#include "skinning.h"
#include <stdio.h>
#include <string.h>
#define REQUIRE(x) do {if(!(x)){fprintf(stderr,"skinning ABI failure line %d\n",__LINE__);return 1;}}while(0)
int main(void){
  int32_t ranges[]={0,1,2},joints[]={0,0},modes[]={0,0};float weights[]={1,1};
  float palette[]={1,0,0,0,0,1,0,0,0,0,1,0,0,0,0,1},input[20]={1,2,3},output[20],before[20];
  for(int i=0;i<20;i++)before[i]=output[i]=-123;
  REQUIRE(ysm_skin_abi()==1);
  REQUIRE(ysm_skin(ranges,3,joints,2,weights,2,modes,2,0,0,palette,16,input,20,output,19,0,0)==-1);
  REQUIRE(memcmp(output,before,sizeof(output))==0);
  joints[1]=9;REQUIRE(ysm_skin(ranges,3,joints,2,weights,2,modes,2,0,0,palette,16,input,20,output,20,0,0)==-1);
  REQUIRE(memcmp(output,before,sizeof(output))==0);joints[1]=0;
  REQUIRE(ysm_skin(ranges,3,joints,2,weights,2,modes,2,0,0,palette,16,input,20,output,20,0,0)==0);
  REQUIRE(output[0]==1&&output[1]==2&&output[2]==3);
  puts("skinning public C ABI, late invalid input and atomic output passed");return 0;
}
