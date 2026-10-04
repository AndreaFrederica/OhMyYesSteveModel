/* Maintainer-only fixture authoring through nanoem's original PMM serializer. */
#include "nanoem/ext/document.h"
#include "nanoem/ext/document_p.h"
#include <stdio.h>
#include <string.h>

static nanoem_status_t status;
static nanoem_unicode_string_factory_t *factory;
static nanoem_unicode_string_t *str(const char *s) { return nanoemUnicodeStringFactoryCreateString(factory,(const nanoem_u8_t*)s,strlen(s),&status); }
static void check(const char *operation) { if(status!=NANOEM_STATUS_SUCCESS) { fprintf(stderr,"%s: %d\n",operation,status);exit(2); } }
static nanoem_buffer_t *read_file(const char *path) {
    FILE *input=fopen(path,"rb");if(!input) exit(3);fseek(input,0,SEEK_END);long size=ftell(input);rewind(input);
    unsigned char *bytes=malloc(size);if(fread(bytes,1,size,input)!=(size_t)size) exit(3);fclose(input);
    return nanoemBufferCreate(bytes,size,&status);
}
static nanoem_model_t *load_model(void *data,const nanoem_unicode_string_t *path,nanoem_unicode_string_factory_t *f,nanoem_status_t *s) {
    (void)path;nanoem_buffer_t *input=read_file((const char*)data);nanoem_model_t *model=nanoemModelCreate(f,s);nanoemModelLoadFromBuffer(model,input,s);return model;
}
int main(int argc,char **argv) {
    if(argc==4 && strcmp(argv[1],"--read")==0) {
        factory=nanoemUnicodeStringFactoryCreate(&status);nanoem_document_t *d=nanoemDocumentCreate(factory,&status);
        nanoemDocumentSetParseModelCallback(d,load_model);nanoemDocumentSetParseModelCallbackUserData(d,argv[3]);
        nanoem_buffer_t *data=read_file(argv[2]);nanoemDocumentLoadFromBuffer(d,data,&status);check("PMM external-model reader");
        printf("PMM%d reader accepted %zu models, first schema: %zu bones, %zu morphs\n",d->version,d->num_models,d->models[0]->num_bones,d->models[0]->num_morphs);return 0;
    }
    if(argc!=2) return 1;
    factory=nanoemUnicodeStringFactoryCreate(&status);
    nanoem_mutable_document_t *document=nanoemMutableDocumentCreate(factory,&status);
    nanoem_document_t *origin=nanoemMutableDocumentGetOrigin(document);
    nanoem_mutable_document_camera_t *camera=nanoemMutableDocumentCameraCreate(document,&status);
    nanoem_mutable_document_light_t *light=nanoemMutableDocumentLightCreate(document,&status);
    nanoem_mutable_document_gravity_t *gravity=nanoemMutableDocumentGravityCreate(document,&status);
    nanoem_mutable_document_self_shadow_t *shadow=nanoemMutableDocumentSelfShadowCreate(document,&status);
    nanoemMutableDocumentSetCameraObject(document,camera);nanoemMutableDocumentSetLightObject(document,light);
    nanoemMutableDocumentSetGravityObject(document,gravity);nanoemMutableDocumentSetSelfShadowObject(document,shadow);
    nanoemMutableDocumentSetAudioPath(document,str("C:\\Dance\\audio.wav"),&status);nanoemMutableDocumentSetAudioEnabled(document,1);
    nanoemMutableDocumentSetBackgroundVideoPath(document,str("C:\\Dance\\background.avi"),&status);
    nanoemMutableDocumentSetBackgroundImagePath(document,str("images/stage.bmp"),&status);
    nanoemMutableDocumentSetPreferredFPS(document,60);nanoemMutableDocumentSetCurrentFrameIndex(document,15);
    nanoemMutableDocumentSetEndFrameIndex(document,90);nanoemMutableDocumentSetLoopEnabled(document,1);
    const nanoem_f32_t translation[4]={1,2,3,0},zero[4]={0,0,0,0},rotation[4]={0,0,0,1};
    for(int m=0;m<2;m++) {
        nanoem_mutable_document_model_t *model=nanoemMutableDocumentModelCreate(document,&status);
        nanoemMutableDocumentModelSetName(model,NANOEM_LANGUAGE_TYPE_JAPANESE,str(m?"second":"first"),&status);
        nanoemMutableDocumentModelSetName(model,NANOEM_LANGUAGE_TYPE_ENGLISH,str(m?"second-en":"first-en"),&status);
        nanoemMutableDocumentModelSetPath(model,str(m?"models/second.pmx":"C:\\Dance\\models\\first.pmd"),&status);
        nanoemMutableDocumentInsertModelObject(document,model,-1,&status);
        const char *names[]={"root","child"};
        for(int i=0;i<2;i++) nanoemMutableDocumentModelRegisterBone(model,str(names[i]),&status);
        nanoemMutableDocumentModelRegisterMorph(model,str("smile"),&status);
        for(int i=0;i<2;i++) {
            nanoem_mutable_document_model_bone_keyframe_t *key=nanoemMutableDocumentModelBoneKeyframeCreate(model,str(names[i]),&status);
            nanoemMutableDocumentModelBoneKeyframeSetTranslation(key,zero);nanoemMutableDocumentModelBoneKeyframeSetOrientation(key,rotation);
            nanoemMutableDocumentModelAddBoneKeyframeObject(model,key,str(names[i]),0,&status);check("initial bone");
            nanoemMutableDocumentModelBoneKeyframeSetTranslation(key,translation);
            nanoemMutableDocumentModelBoneKeyframeSetPhysicsSimulationDisabled(key,i);
            nanoemMutableDocumentModelAddBoneKeyframeObject(model,key,str(names[i]),30+i*30,&status);check("bone");
            nanoemMutableDocumentModelBoneKeyframeDestroy(key);
        }
        nanoem_mutable_document_model_morph_keyframe_t *morph=nanoemMutableDocumentModelMorphKeyframeCreate(model,str("smile"),&status);
        nanoemMutableDocumentModelAddMorphKeyframeObject(model,morph,str("smile"),0,&status);check("initial morph");
        nanoemMutableDocumentModelMorphKeyframeSetWeight(morph,.75f);nanoemMutableDocumentModelAddMorphKeyframeObject(model,morph,str("smile"),45,&status);check("morph");
        nanoemMutableDocumentModelMorphKeyframeDestroy(morph);
        nanoem_mutable_document_model_keyframe_t *key=nanoemMutableDocumentModelKeyframeCreate(model,&status);
        nanoemMutableDocumentModelKeyframeSetVisible(key,0);nanoemMutableDocumentModelAddModelKeyframeObject(model,key,90,&status);check("model");nanoemMutableDocumentModelKeyframeDestroy(key);
        nanoemMutableDocumentModelDestroy(model);
    }
    nanoem_mutable_document_accessory_t *accessory=nanoemMutableDocumentAccessoryCreate(document,&status);
    nanoemMutableDocumentAccessorySetName(accessory,str("stage"),&status);nanoemMutableDocumentAccessorySetPath(accessory,str("C:\\Dance\\stage.x"),&status);
    nanoemMutableDocumentInsertAccessoryObject(document,accessory,-1,&status);
    nanoem_mutable_document_accessory_keyframe_t *ak=nanoemMutableDocumentAccessoryKeyframeCreate(accessory,&status);
    nanoemMutableDocumentAccessoryKeyframeSetTranslation(ak,translation);nanoemMutableDocumentAccessoryKeyframeSetScaleFactor(ak,2);
    nanoemMutableDocumentAccessoryKeyframeSetOpacity(ak,.5f);nanoemMutableDocumentAccessoryKeyframeSetVisible(ak,1);
    nanoemMutableDocumentAccessoryAddAccessoryKeyframeObject(accessory,ak,60,&status);check("accessory");
    nanoem_mutable_document_camera_keyframe_t *ck=nanoemMutableDocumentCameraKeyframeCreate(camera,&status);
    nanoemMutableDocumentCameraKeyframeSetLookAt(ck,translation);nanoemMutableDocumentCameraKeyframeSetDistance(ck,-40);nanoemMutableDocumentCameraKeyframeSetFov(ck,35);
    nanoemMutableDocumentCameraAddCameraKeyframeObject(camera,ck,30,&status);check("camera");
    nanoem_mutable_document_light_keyframe_t *lk=nanoemMutableDocumentLightKeyframeCreate(light,&status);
    nanoemMutableDocumentLightKeyframeSetColor(lk,translation);nanoemMutableDocumentLightAddLightKeyframeObject(light,lk,30,&status);check("light");
    nanoem_mutable_document_gravity_keyframe_t *gk=nanoemMutableDocumentGravityKeyframeCreate(gravity,&status);
    nanoemMutableDocumentGravityKeyframeSetAcceleration(gk,12);nanoemMutableDocumentGravityKeyframeSetDirection(gk,translation);
    nanoemMutableDocumentGravityAddGravityKeyframeObject(gravity,gk,30,&status);check("gravity");
    nanoem_mutable_document_self_shadow_keyframe_t *sk=nanoemMutableDocumentSelfShadowKeyframeCreate(shadow,&status);
    nanoemMutableDocumentSelfShadowKeyframeSetDistance(sk,.012f);nanoemMutableDocumentSelfShadowKeyframeSetMode(sk,2);
    nanoemMutableDocumentSelfShadowAddSelfShadowKeyframeObject(shadow,sk,30,&status);check("shadow");
    nanoem_mutable_buffer_t *buffer=nanoemMutableBufferCreate(&status);
    nanoemMutableDocumentSaveToBuffer(document,buffer,&status);check("save");
    nanoem_buffer_t *data=nanoemMutableBufferCreateBufferObject(buffer,&status);
    FILE *output=fopen(argv[1],"wb");if(!output) return 3;
    fwrite(nanoemBufferGetDataPtr(data),1,nanoemBufferGetLength(data),output);fclose(output);
    nanoem_document_t *loaded=nanoemDocumentCreate(factory,&status);nanoemDocumentLoadFromBuffer(loaded,data,&status);check("independent reader roundtrip");
    printf("PMM writer/reader accepted %zu bytes, %zu models, %zu accessories\n",nanoemBufferGetLength(data),loaded->num_models,loaded->num_accessories);
    for(size_t m=0;m<loaded->num_models;m++) for(size_t i=0;i<loaded->models[m]->num_all_bone_keyframes;i++) {
        const nanoem_document_base_keyframe_t *k=&loaded->models[m]->all_bone_keyframes_ptr[i]->base;
        printf("model %zu bone record %zu: id=%d frame=%u prev=%zu next=%zu\n",m,i,k->object_index,k->frame_index,k->previous_keyframe_index,k->next_keyframe_index);
    }
    nanoemDocumentDestroy(loaded);nanoemBufferDestroy(data);nanoemMutableBufferDestroy(buffer);nanoemMutableDocumentDestroy(document);nanoemUnicodeStringFactoryDestroy(factory);
    return 0;
}
