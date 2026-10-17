/* YSM adapter around the pinned, unmodified ufbx library. No filesystem or process access. */
#include "ufbx.h"
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <stdio.h>
#include <stdarg.h>
#include <math.h>
#include <limits.h>

static ufbx_scene *source_scene, *evaluated_scene;
static size_t memory_limit, output_limit, element_limit, string_limit;
static char error_text[2048];
static char *output;
static size_t output_size, output_capacity;
static int failed;
/* Computed once from the resident source scene; evaluating every frame must
   not rebuild cluster maps or allocate temporary comparison tables. */
static int skin_semantics_supported=1;
static bool fbx_mesh_skin_supported(ufbx_mesh *m);
static size_t snapshot_elements;
static bool json_string, json_escape, json_scalar;
/* Property blobs are editor metadata, not geometry, skin or animation input.
   Keeping an unbounded Base64 copy for every FBX element can multiply a
   modest binary export into hundreds of megabytes before Java sees it. */
#define PROPERTY_BLOB_LIMIT 65536u

int ysm_fbx_abi(void) { return 2; }
/* JNI ABI is separate from the scalar reactor ABI because nPrepare carries
   explicit Skin-selection arrays while the WASM reactor keeps its old shape. */
int ysm_fbx_native_abi(void) { return 1; }
const char *ysm_fbx_error(void) { return error_text; }
const char *ysm_fbx_output(void) { return output; }
int ysm_fbx_output_size(void) { return failed ? -1 : (int)output_size; }
static int error(const char *message) { snprintf(error_text,sizeof(error_text),"%s",message);failed=1;return -1; }
static int budget_error(const char *kind, size_t actual, size_t limit) {
    snprintf(error_text,sizeof(error_text),"FBX %s budget exceeded (actual=%zu, limit=%zu, outputBytes=%zu, outputLimit=%zu)", kind, actual, limit, output_size, output_limit);
    failed=1;
    return -1;
}
static int ufbx_error_result(ufbx_error *value) { ufbx_format_error(error_text,sizeof(error_text),value);failed=1;return -1; }
void ysm_fbx_destroy(void) {
    ufbx_free_scene(evaluated_scene);ufbx_free_scene(source_scene);evaluated_scene=NULL;source_scene=NULL;
    free(output);output=NULL;output_size=output_capacity=0;skin_semantics_supported=1;
}
static bool no_file(void *user,ufbx_stream *stream,const char *path,size_t length,const ufbx_open_file_info *info) {
    (void)user;(void)stream;(void)path;(void)length;(void)info;return false;
}
int ysm_fbx_load(const void *bytes,int length,int memory_bytes,int output_bytes,int elements,int string_bytes) {
    ysm_fbx_destroy();error_text[0]=0;failed=0;
    if(!bytes || length<=0 || memory_bytes<=0 || output_bytes<=0 || elements<=0 || string_bytes<=0) return error("Invalid FBX input budgets");
    memory_limit=(size_t)memory_bytes;output_limit=(size_t)output_bytes;element_limit=(size_t)elements;string_limit=(size_t)string_bytes;
    ufbx_load_opts opts={0};ufbx_error e;
    opts.file_format=UFBX_FILE_FORMAT_FBX;opts.strict=true;opts.clean_skin_weights=false;
    opts.temp_allocator.memory_limit=memory_limit;opts.result_allocator.memory_limit=memory_limit;
    opts.node_depth_limit=512;opts.open_file_cb.fn=no_file;
    // The bridge exposes typed scene data, not the raw FBX DOM. Retaining the
    // DOM duplicates the whole source document and needlessly raises peak
    // memory for large exports. W components remain enabled because authored
    // tangent/bitangent handedness is part of the lossless source contract.
    opts.retain_dom=false;opts.retain_vertex_attrib_w=true;
    opts.evaluate_skinning=false;opts.generate_missing_normals=true;opts.use_blender_pbr_material=true;
    opts.target_axes=ufbx_axes_right_handed_y_up;opts.target_unit_meters=1.0;
    source_scene=ufbx_load_memory(bytes,(size_t)length,&opts,&e);
    if(!source_scene) return ufbx_error_result(&e);
    for(size_t i=0;i<source_scene->meshes.count;i++) if(!fbx_mesh_skin_supported(source_scene->meshes.data[i])) skin_semantics_supported=0;
    size_t count=source_scene->elements.count;
    for(size_t i=0;i<source_scene->meshes.count;i++) {
        ufbx_mesh *mesh=source_scene->meshes.data[i];
        if(mesh->num_indices>element_limit || count>element_limit-mesh->num_indices) { ysm_fbx_destroy();return error("FBX element budget exceeded"); }
        count+=mesh->num_indices;
    }
    if(count>element_limit) { ysm_fbx_destroy();return error("FBX element budget exceeded"); }
    return 0;
}
int ysm_fbx_apply_skin_selection(const uint32_t *mesh_elements,const uint32_t *skin_elements,size_t count) {
    if(!source_scene || (count && (!mesh_elements || !skin_elements))) return error("Invalid FBX Skin selection");
    for(size_t i=0;i<count;i++) {
        ufbx_mesh *mesh=NULL;
        for(size_t j=0;j<source_scene->meshes.count;j++) if(source_scene->meshes.data[j]->element_id==mesh_elements[i]) { mesh=source_scene->meshes.data[j];break; }
        if(!mesh) return error("FBX mapping mesh element does not exist");
        ufbx_skin_deformer *selected=NULL;
        for(size_t j=0;j<mesh->skin_deformers.count;j++) {
            ufbx_skin_deformer *skin=mesh->skin_deformers.data[j];
            if(skin->element_id==skin_elements[i] || skin->typed_id==skin_elements[i]) { selected=skin;break; }
        }
        if(!selected) return error("FBX mapping Skin is not attached to mesh");
        mesh->skin_deformers.data[0]=selected;mesh->skin_deformers.count=1;
    }
    skin_semantics_supported=1;
    for(size_t j=0;j<source_scene->meshes.count;j++) if(!fbx_mesh_skin_supported(source_scene->meshes.data[j])) { skin_semantics_supported=0;break; }
    return 0;
}
int ysm_fbx_evaluate(int stack,double seconds) {
    if(!source_scene || !isfinite(seconds) || stack< -1) return error("Invalid FBX animation sample");
    /* A model with no explicit AnimationStack still has the default ufbx
       animation descriptor.  The host can transiently carry clip zero while
       switching a source that exposes no clips; treat that request as rest
       instead of rejecting the entire native session. */
    if(source_scene->anim_stacks.count==0) {
        if(stack>0) return error("Invalid FBX animation sample");
        stack=-1;
    } else if(stack>=(int)source_scene->anim_stacks.count) return error("Invalid FBX animation sample");
    ufbx_evaluate_opts opts={0};ufbx_error e;opts.evaluate_skinning=false;opts.open_file_cb.fn=no_file;
    opts.temp_allocator.memory_limit=memory_limit;opts.result_allocator.memory_limit=memory_limit;
    const ufbx_anim *animation=stack<0?source_scene->anim:source_scene->anim_stacks.data[stack]->anim;
    ufbx_scene *next=ufbx_evaluate_scene(source_scene,animation,seconds,&opts,&e);
    if(!next) return ufbx_error_result(&e);
    ufbx_free_scene(evaluated_scene);evaluated_scene=next;failed=0;return 0;
}
static void append(const char *data,size_t length) {
    if(failed) return;
    // Bound all transferred values, including properties, keys, influences and nested attributes.
    // Count JSON tokens as emitted so no source category can bypass the aggregate budget.
    for(size_t i=0;i<length;i++) {
        char c=data[i];bool value=false;
        if(json_string) { if(json_escape) json_escape=false;else if(c=='\\') json_escape=true;else if(c=='"') json_string=false; }
        else if(c=='"') { json_string=true;json_scalar=false;value=true; }
        else if(c=='['||c=='{') { json_scalar=false;value=true; }
        else if(c==','||c==':'||c==']'||c=='}'||c==' '||c=='\n'||c=='\r'||c=='\t') json_scalar=false;
        else if(!json_scalar) { json_scalar=true;value=true; }
        if(value && ++snapshot_elements>element_limit) { budget_error("snapshot element",snapshot_elements,element_limit);return; }
    }
    if(length>output_limit || output_size>output_limit-length) { budget_error("snapshot output",output_size+length,output_limit);return; }
    size_t needed=output_size+length+1;
    if(needed>output_capacity) {
        size_t next=output_capacity?output_capacity:4096;
        /* Avoid doubling a large snapshot buffer.  A 150 MiB snapshot used
           to briefly reserve a 256 MiB native buffer before JNI allocated the
           Java byte[].  Small bounded growth keeps the resident peak close to
           the actual output while retaining the same hard output limit. */
        const size_t step=8u*1024u*1024u;
        while(next<needed) { if(next>output_limit-step) { next=output_limit+1;break; } next+=step; }
        char *buffer=(char*)realloc(output,next);if(!buffer) { error("FBX snapshot memory exhausted");return; }
        output=buffer;output_capacity=next;
    }
    memcpy(output+output_size,data,length);output_size+=length;output[output_size]=0;
}
static void text(const char *s) { append(s,strlen(s)); }
static void format(const char *pattern,...) {
    char b[512];va_list args;va_start(args,pattern);int n=vsnprintf(b,sizeof(b),pattern,args);va_end(args);
    if(n<0 || n>=(int)sizeof(b)) { error("FBX snapshot format overflow");return; }append(b,(size_t)n);
}
/* FBX geometry is ultimately consumed as float draw data. Ten significant
   digits preserve sub-micron precision at avatar scale while cutting the
   decimal token footprint (and the Gson object count) substantially. */
static void number(double v) { if(!isfinite(v)) error("Non-finite FBX value");else format("%.10g",v); }
static void string(ufbx_string s) {
    if(s.length>string_limit) { error("FBX string budget exceeded");return; }
    text("\"");for(size_t i=0;i<s.length&&!failed;i++) {
        unsigned char c=(unsigned char)s.data[i];if(c=='"'||c=='\\') { char b[2]={'\\',(char)c};append(b,2); }
        else if(c<32) format("\\u%04x",c);else append((const char*)&c,1);
    }text("\"");
}
static void blob(ufbx_blob b) {
    static const char alphabet[]="ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    const uint8_t *p=(const uint8_t*)b.data;text("\"");
    for(size_t i=0;i<b.size&&!failed;i+=3) {
        uint32_t v=(uint32_t)p[i]<<16;if(i+1<b.size) v|=(uint32_t)p[i+1]<<8;if(i+2<b.size) v|=p[i+2];
        char s[4]={alphabet[(v>>18)&63],alphabet[(v>>12)&63],i+1<b.size?alphabet[(v>>6)&63]:'=',i+2<b.size?alphabet[v&63]:'='};append(s,4);
    }text("\"");
}
static void property_blob(ufbx_blob b) {
    if (b.size > PROPERTY_BLOB_LIMIT) { text("\"\""); return; }
    blob(b);
}
static void vector(const ufbx_real *v,size_t n) { text("[");for(size_t i=0;i<n;i++) { if(i) text(",");number(v[i]); }text("]"); }
static void matrix(ufbx_matrix m) { vector(m.v,12); }
static void ints(ufbx_uint32_list list) { text("[");for(size_t i=0;i<list.count;i++) { if(i) text(",");format("%u",list.data[i]); }text("]"); }
static int element_id(const ufbx_element *element) { return element?(int)element->element_id:-1; }
static void props(const ufbx_props *p) {
    // Every level is retained: evaluated overrides precede immutable source/template defaults.
    text("[");bool first=true;for(;p;p=p->defaults) {
        if(!first) text(",");first=false;text("[");
        for(size_t i=0;i<p->props.count;i++) {
            const ufbx_prop *v=&p->props.data[i];if(i) text(",");text("{\"name\":");string(v->name);
            format(",\"type\":%u,\"flags\":%u,\"integer\":\"%lld\",\"value\":",(unsigned)v->type,(unsigned)v->flags,(long long)v->value_int);
            vector(v->value_real_arr,4);text(",\"string\":");string(v->value_str);text(",\"blob\":");property_blob(v->value_blob);text("}");
        }text("]");
    }text("]");
}
static void elements(ufbx_scene *scene) {
    text(",\"elements\":[");for(size_t i=0;i<scene->elements.count;i++) {
        ufbx_element *e=scene->elements.data[i];if(i) text(",");format("{\"id\":%u,\"typedId\":%u,\"type\":%u,\"name\":",e->element_id,e->typed_id,(unsigned)e->type);
        string(e->name);
        /* Runtime evaluation consumes typed nodes, meshes, skins, curves and
           material records directly. The raw FBX property/connection DOM is
           editor metadata and can be enormous for Unity exports; retaining
           it here duplicated the same values in every default layer. Keep a
           valid empty shape so the public source record remains compatible.
           A future authoring adapter can request the raw DOM explicitly. */
        text(",\"properties\":[],\"connections\":[]}");
    }text("]");
}
static void nodes(ufbx_scene *scene) {
    text(",\"nodes\":[");for(size_t i=0;i<scene->nodes.count;i++) {
        ufbx_node *n=scene->nodes.data[i];if(i) text(",");format("{\"element\":%u,\"parent\":%d,\"mesh\":%d,\"light\":%d,\"camera\":%d,\"visible\":%s,\"inheritMode\":%u,\"local\":",
            n->element_id,n->parent?(int)n->parent->typed_id:-1,n->mesh?(int)n->mesh->typed_id:-1,n->light?(int)n->light->typed_id:-1,n->camera?(int)n->camera->typed_id:-1,n->visible?"true":"false",(unsigned)n->inherit_mode);
        matrix(n->node_to_parent);text(",\"world\":");matrix(n->node_to_world);text(",\"geometryLocal\":");matrix(n->geometry_to_node);text(",\"geometryWorld\":");matrix(n->geometry_to_world);
        text(",\"materials\":[");for(size_t j=0;j<n->materials.count;j++) { if(j) text(",");format("%u",n->materials.data[j]->typed_id); }text("]}");
    }text("]");
}
static void attrib3(ufbx_vertex_vec3 a,size_t indices) {
    text("[");if(a.exists) for(size_t i=0;i<indices;i++) { if(i) text(",");ufbx_vec3 v=ufbx_get_vertex_vec3(&a,i);vector(v.v,3); }text("]");
}
static void attrib_w(ufbx_vertex_vec3 a,size_t indices) {
    text("[");if(a.exists&&a.values_w.count) for(size_t i=0;i<indices;i++) { if(i) text(",");number(ufbx_get_vertex_w_vec3(&a,i)); }text("]");
}
static void meshes(ufbx_scene *scene,bool source) {
    text(",\"meshes\":[");for(size_t i=0;i<scene->meshes.count;i++) {
        ufbx_mesh *m=scene->meshes.data[i];if(i) text(",");format("{\"element\":%u,\"skinnedLocal\":%s,\"position\":",m->element_id,m->skinned_is_local?"true":"false");
        attrib3(m->vertex_position,m->num_indices);text(",\"normal\":");attrib3(m->vertex_normal,m->num_indices);
        if(source) {
            text(",\"positionW\":");attrib_w(m->vertex_position,m->num_indices);text(",\"normalW\":");attrib_w(m->vertex_normal,m->num_indices);
            format(",\"generatedNormals\":%s,\"allDeformers\":[",m->generated_normals?"true":"false");
            for(size_t j=0;j<m->all_deformers.count;j++) { if(j) text(",");format("%u",m->all_deformers.data[j]->element_id); }text("]");
            text(",\"controlVertices\":");ints(m->vertex_indices);text(",\"uvSets\":[");
            for(size_t j=0;j<m->uv_sets.count;j++) {
                ufbx_uv_set *uv=&m->uv_sets.data[j];if(j) text(",");text("{\"name\":");string(uv->name);text(",\"uv\":[");
                for(size_t k=0;k<m->num_indices;k++) { if(k) text(",");ufbx_vec2 v=ufbx_get_vertex_vec2(&uv->vertex_uv,k);vector(v.v,2); }
                text("],\"tangent\":");attrib3(uv->vertex_tangent,m->num_indices);text(",\"bitangent\":");attrib3(uv->vertex_bitangent,m->num_indices);
                text(",\"tangentW\":");attrib_w(uv->vertex_tangent,m->num_indices);text(",\"bitangentW\":");attrib_w(uv->vertex_bitangent,m->num_indices);text("}");
            }text("],\"colorSets\":[");
            for(size_t j=0;j<m->color_sets.count;j++) {
                ufbx_color_set *colors=&m->color_sets.data[j];if(j) text(",");text("{\"name\":");string(colors->name);text(",\"color\":[");
                for(size_t k=0;k<m->num_indices;k++) { if(k) text(",");ufbx_vec4 v=ufbx_get_vertex_vec4(&colors->vertex_color,k);vector(v.v,4); }text("]}");
            }text("],\"faces\":[");
            size_t cap=m->max_face_triangles*3;uint32_t *triangles=(uint32_t*)malloc(cap*sizeof(uint32_t));
            if(cap&&!triangles) { error("FBX triangulation memory exhausted");return; }
            for(size_t j=0;j<m->faces.count;j++) {
                ufbx_face face=m->faces.data[j];if(j) text(",");format("{\"begin\":%u,\"count\":%u,\"material\":%u,\"triangles\":[",face.index_begin,face.num_indices,m->face_material.count?m->face_material.data[j]:0);
                if(face.num_indices>=3) { uint32_t count=ufbx_triangulate_face(triangles,cap,m,face)*3;
                    for(uint32_t k=0;k<count;k++) { if(k) text(",");format("%u",triangles[k]); }
                }text("]}");
            }free(triangles);text("],\"skinDeformers\":[");
            for(size_t j=0;j<m->skin_deformers.count;j++) { if(j) text(",");format("%u",m->skin_deformers.data[j]->typed_id); }
            text("],\"blendDeformers\":[");for(size_t j=0;j<m->blend_deformers.count;j++) { if(j) text(",");format("%u",m->blend_deformers.data[j]->typed_id); }
            format("],\"cacheDeformers\":%u,\"subdivisionLevel\":%u",(unsigned)m->cache_deformers.count,m->subdivision_render_levels);
        }text("}");
    }text("]");
}
static void skins(ufbx_scene *scene) {
    text(",\"skins\":[");for(size_t i=0;i<scene->skin_deformers.count;i++) {
        ufbx_skin_deformer *s=scene->skin_deformers.data[i];bool has_compact_weights=s->vertices.count>0&&s->weights.count>0;if(i) text(",");format("{\"element\":%u,\"method\":%u,\"clusters\":[",s->element_id,(unsigned)s->skinning_method);
        for(size_t j=0;j<s->clusters.count;j++) { ufbx_skin_cluster *c=s->clusters.data[j];if(j) text(",");format("{\"element\":%u,\"bone\":%d,\"geometryToBone\":",c->element_id,c->bone_node?(int)c->bone_node->typed_id:-1);
            matrix(c->geometry_to_bone);text(",\"meshToBone\":");matrix(c->mesh_node_to_bone);text(",\"bindWorld\":");matrix(c->bind_to_world);
            text(",\"linkMode\":");
            string(ufbx_find_string(&c->element.props,"Mode",(ufbx_string){"",0}));
            /* ufbx's compact vertex/weight table is the canonical runtime
               representation.  The per-cluster raw arrays duplicate it for
               normal meshes, so transfer them only for exporters that omit
               the compact table and need the fallback path. */
            if(!has_compact_weights) { text(",\"rawVertices\":");ints(c->vertices);text(",\"rawWeights\":");vector(c->weights.data,c->weights.count); }
            text("}");
        }text("],\"vertices\":[");
        for(size_t j=0;j<s->vertices.count;j++) { ufbx_skin_vertex *v=&s->vertices.data[j];if(j) text(",");format("[%u,%u,",v->weight_begin,v->num_weights);number(v->dq_weight);text("]"); }
        text("],\"weights\":[");for(size_t j=0;j<s->weights.count;j++) { if(j) text(",");format("[%u,",s->weights.data[j].cluster_index);number(s->weights.data[j].weight);text("]"); }text("]}");
    }text("]");
}
static void animations(ufbx_scene *scene) {
    text(",\"animations\":[");for(size_t i=0;i<scene->anim_stacks.count;i++) {
        ufbx_anim_stack *s=scene->anim_stacks.data[i];if(i) text(",");format("{\"element\":%u,\"begin\":",s->element_id);number(s->time_begin);text(",\"end\":");number(s->time_end);text(",\"layers\":[");
        for(size_t j=0;j<s->layers.count;j++) { if(j) text(",");format("%u",s->layers.data[j]->typed_id); }text("]}");
    }text("],\"animationLayers\":[");
    for(size_t i=0;i<scene->anim_layers.count;i++) {
        ufbx_anim_layer *l=scene->anim_layers.data[i];if(i) text(",");format("{\"element\":%u,\"weight\":",l->element_id);number(l->weight);
        format(",\"blended\":%s,\"additive\":%s,\"composeRotation\":%s,\"composeScale\":%s,\"properties\":[",l->blended?"true":"false",l->additive?"true":"false",l->compose_rotation?"true":"false",l->compose_scale?"true":"false");
        for(size_t j=0;j<l->anim_props.count;j++) { ufbx_anim_prop *p=&l->anim_props.data[j];if(j) text(",");format("{\"element\":%u,\"name\":",p->element->element_id);string(p->prop_name);
            text(",\"default\":");vector(p->anim_value->default_value.v,3);text(",\"curves\":[");for(int k=0;k<3;k++) { if(k) text(",");format("%d",p->anim_value->curves[k]?(int)p->anim_value->curves[k]->typed_id:-1); }text("]}");
        }text("]}");
    }text("],\"curves\":[");for(size_t i=0;i<scene->anim_curves.count;i++) {
        ufbx_anim_curve *c=scene->anim_curves.data[i];if(i) text(",");format("{\"element\":%u,\"pre\":[%u,%d],\"post\":[%u,%d],\"keys\":[",c->element_id,(unsigned)c->pre_extrapolation.mode,c->pre_extrapolation.repeat_count,(unsigned)c->post_extrapolation.mode,c->post_extrapolation.repeat_count);
        for(size_t j=0;j<c->keyframes.count;j++) { ufbx_keyframe *k=&c->keyframes.data[j];if(j) text(",");text("[");number(k->time);text(",");number(k->value);format(",%u,",(unsigned)k->interpolation);number(k->left.dx);text(",");number(k->left.dy);text(",");number(k->right.dx);text(",");number(k->right.dy);text("]"); }
        text("]}");
    }text("]");
}
static void textures(ufbx_scene *scene) {
    text(",\"textures\":[");for(size_t i=0;i<scene->textures.count;i++) {
        ufbx_texture *t=scene->textures.data[i];if(i) text(",");format("{\"element\":%u,\"type\":%u,\"relativePath\":",t->element_id,(unsigned)t->type);string(t->relative_filename);
        text(",\"absolutePath\":");string(t->absolute_filename);text(",\"rawRelativePath\":");blob(t->raw_relative_filename);text(",\"rawAbsolutePath\":");blob(t->raw_absolute_filename);
        text(",\"content\":");blob(t->content);text(",\"uvSet\":");string(t->uv_set);format(",\"wrap\":[%u,%u],\"uvTransform\":",(unsigned)t->wrap_u,(unsigned)t->wrap_v);matrix(t->uv_to_texture);
        text(",\"layers\":[");for(size_t j=0;j<t->layers.count;j++) { ufbx_texture_layer *l=&t->layers.data[j];if(j) text(",");format("[%u,%u,",l->texture->typed_id,(unsigned)l->blend_mode);number(l->alpha);text("]"); }text("]}");
    }text("]");
}
#include "fbx_details.h"
#include "fbx_deform.h"
int ysm_fbx_snapshot(int source,int follow_mesh) {
    if(!source_scene || (!source&&!evaluated_scene)) return error("FBX scene unavailable");
    /* source=1 emits the immutable source document, source=0 an evaluated
       frame, and source=2 only evaluated light/camera properties. The latter
       keeps typed geometry users from rebuilding a giant draw JSON document
       just to expose animated scene effects. */
    ufbx_scene *s=source==1?source_scene:evaluated_scene;failed=0;output_size=0;snapshot_elements=0;json_string=json_escape=json_scalar=false;
    format("{\"abi\":2,\"version\":%u,\"ascii\":%s,\"sourceUnitMeters\":",s->metadata.version,s->metadata.ascii?"true":"false");number(s->settings.unit_meters);
    text(",\"framesPerSecond\":");number(s->settings.frames_per_second);text(",\"creator\":");string(s->metadata.creator);
    format(",\"constraintCount\":%u",(unsigned)s->constraints.count);nodes(s);
    if(source==1) {
        meshes(s,true);
        elements(s);shapes(s,true);materials(s);lights_cameras(s);constraints(s);warnings(s);textures(s);
        skins(s);animations(s);
    } else {
        // Evaluated frames only contain mutable pose/geometry data. Elements,
        // materials, textures and animation metadata are immutable and are
        // reused from the source document by FbxSnapshots.
        lights_cameras(s);if(source!=2)draws(s,follow_mesh!=0);
    }
    text("}");return failed?-1:0;
}

/*
 * Typed frame ABI.  The source snapshot remains the lossless authoring
 * contract, but animation samples use caller-owned buffers so they never
 * become a JSON string or a Gson tree.  Counts are stable for a loaded FBX
 * source and therefore let Java allocate one bounded set of direct buffers.
 */
static int geometry_counts_for(ufbx_scene *s,int *draw_count,size_t *positions,size_t *normals,
                               size_t *tangents,size_t *bitangents,int *node_count) {
    size_t draws_count=0,pos=0,nor=0,tan=0,bit=0;
    if(!s) return error("FBX scene unavailable");
    for(size_t i=0;i<s->nodes.count;i++) {
        ufbx_node *node=s->nodes.data[i]; ufbx_mesh *m=node->mesh;
        if(!m) continue;
        draws_count++;
        if(draws_count>INT_MAX || m->num_indices>SIZE_MAX/3 || pos>SIZE_MAX-m->num_indices*3)
            return error("FBX typed geometry count overflow");
        pos+=m->num_indices*3;
        if(m->vertex_normal.exists) { if(nor>SIZE_MAX-m->num_indices*3) return error("FBX typed normal count overflow"); nor+=m->num_indices*3; }
        for(size_t u=0;u<m->uv_sets.count;u++) {
            ufbx_uv_set *uv=&m->uv_sets.data[u];
            if(uv->vertex_tangent.exists) { if(tan>SIZE_MAX-m->num_indices*3) return error("FBX typed tangent count overflow"); tan+=m->num_indices*3; }
            if(uv->vertex_bitangent.exists) { if(bit>SIZE_MAX-m->num_indices*3) return error("FBX typed bitangent count overflow"); bit+=m->num_indices*3; }
        }
    }
    if(s->nodes.count>INT_MAX || pos>INT_MAX || nor>INT_MAX || tan>INT_MAX || bit>INT_MAX)
        return error("FBX typed geometry exceeds JNI buffer limits");
    *draw_count=(int)draws_count;*positions=pos;*normals=nor;*tangents=tan;*bitangents=bit;*node_count=(int)s->nodes.count;return 0;
}
int ysm_fbx_geometry_counts(int *draw_count,int *position_scalars,int *normal_scalars,int *tangent_scalars,
                            int *bitangent_scalars,int *node_count) {
    size_t p=0,n=0,t=0,b=0;int nodes=0;
    int result=geometry_counts_for(source_scene,draw_count,&p,&n,&t,&b,&nodes);
    if(result<0) return result;
    *position_scalars=(int)p;*normal_scalars=(int)n;*tangent_scalars=(int)t;*bitangent_scalars=(int)b;*node_count=nodes;return 0;
}

static int typed_float(float *out,size_t capacity,size_t *offset,double value) {
    if(!isfinite(value) || *offset>=capacity) return error("FBX typed geometry output is invalid");
    out[(*offset)++]=(float)value;
    if(!isfinite(out[*offset-1])) return error("FBX typed geometry exceeds float range");
    return 0;
}
static int typed_vec3(float *out,size_t cap,size_t *offset,ufbx_vec3 value) {
    for(int k=0;k<3;k++) if(typed_float(out,cap,offset,value.v[k])<0) return -1; return 0;
}
static int typed_matrix(float *out,size_t cap,size_t *offset,ufbx_matrix value) {
    for(int k=0;k<12;k++) if(typed_float(out,cap,offset,value.v[k])<0) return -1; return 0;
}
static int write_node_matrices(float *local,float *world,float *geometry_local,float *geometry_world,size_t node_n) {
    if(!evaluated_scene) return error("FBX evaluated scene unavailable");
    if(node_n<evaluated_scene->nodes.count) return error("FBX node matrix buffer is too small");
    for(size_t ni=0;ni<evaluated_scene->nodes.count;ni++) {
        ufbx_node *node=evaluated_scene->nodes.data[ni];size_t offset=ni*12;
        if(typed_matrix(local,node_n*12,&offset,node->node_to_parent)<0) return -1;
        offset=ni*12;if(typed_matrix(world,node_n*12,&offset,node->node_to_world)<0) return -1;
        offset=ni*12;if(typed_matrix(geometry_local,node_n*12,&offset,node->geometry_to_node)<0) return -1;
        offset=ni*12;if(typed_matrix(geometry_world,node_n*12,&offset,node->geometry_to_world)<0) return -1;
    }
    return failed?-1:0;
}
int ysm_fbx_evaluate_nodes(int stack,double seconds,float *local,float *world,float *geometry_local,float *geometry_world,size_t node_n) {
    if(ysm_fbx_evaluate(stack,seconds)<0) return -1;return write_node_matrices(local,world,geometry_local,geometry_world,node_n);
}

static ufbx_matrix physics_delta_matrix(const float *values) {
    ufbx_matrix out=ufbx_identity_matrix;
    out.v[0]=values[0];out.v[1]=values[4];out.v[2]=values[8];
    out.v[3]=values[1];out.v[4]=values[5];out.v[5]=values[9];
    out.v[6]=values[2];out.v[7]=values[6];out.v[8]=values[10];
    out.v[9]=values[12];out.v[10]=values[13];out.v[11]=values[14];
    return out;
}
static int physics_node_index(ufbx_scene *s,ufbx_node *needle) {
    if(!needle) return -1;for(size_t i=0;i<s->nodes.count;i++) if(s->nodes.data[i]==needle) return (int)i;return -1;
}
static ufbx_matrix physics_cluster_matrix(ufbx_scene *scene,ufbx_skin_cluster *cluster,const float *physics,size_t physics_nodes) {
    ufbx_matrix matrix=cluster->geometry_to_world;
    int node=physics_node_index(scene,cluster->bone_node);
    if(node>=0&&(size_t)node<physics_nodes) { ufbx_matrix delta=physics_delta_matrix(physics+(size_t)node*16);matrix=ufbx_matrix_mul(&delta,&matrix); }
    return matrix;
}
static bool physics_matrix_is_rigid_transform(const ufbx_matrix *matrix) {
    /* Dual quaternions cannot represent shear. Keep the same tolerance as the
       regular ufbx DQ path and let the caller fall back to the checked Java
       implementation when an authored or physics transform contains it. */
    ufbx_transform t=ufbx_matrix_to_transform(matrix);ufbx_matrix reconstructed=ufbx_transform_to_matrix(&t);
    for(int k=0;k<12;k++) if(fabs((double)reconstructed.v[k]-(double)matrix->v[k])>1e-8*(1.0+fabs((double)matrix->v[k]))) return false;
    return true;
}
static ufbx_matrix physics_skin_vertex_matrix(ufbx_scene *scene,ufbx_skin_deformer *skin,size_t vertex,const float *physics,size_t physics_nodes,const ufbx_matrix *fallback,bool *unsupported) {
    ufbx_skin_vertex sv=skin->vertices.data[vertex];ufbx_matrix linear={0};
    ufbx_quat q0={0},qe={0},first_q0={0};ufbx_vec3 qs={0,0,0};double total=0;
    for(size_t j=0;j<sv.num_weights;j++) {
        ufbx_skin_weight sw=skin->weights.data[sv.weight_begin+j];if(sw.cluster_index>=skin->clusters.count) continue;
        ufbx_skin_cluster *cluster=skin->clusters.data[sw.cluster_index];ufbx_matrix matrix=physics_cluster_matrix(scene,cluster,physics,physics_nodes);
        total+=sw.weight;
        if(sv.dq_weight>0.0f) {
            ufbx_transform t=ufbx_matrix_to_transform(&matrix);ufbx_quat vq0=t.rotation;
            if(j==0) first_q0=vq0;
            if(ufbx_quat_dot(first_q0,vq0)<0.0f) { vq0.x=-vq0.x;vq0.y=-vq0.y;vq0.z=-vq0.z;vq0.w=-vq0.w; }
            ufbx_quat vqt={(ufbx_real)(0.5*t.translation.x),(ufbx_real)(0.5*t.translation.y),(ufbx_real)(0.5*t.translation.z),0};
            ufbx_quat vqe=ufbx_quat_mul(vqt,vq0);
            q0.x+=(ufbx_real)(vq0.x*sw.weight);q0.y+=(ufbx_real)(vq0.y*sw.weight);q0.z+=(ufbx_real)(vq0.z*sw.weight);q0.w+=(ufbx_real)(vq0.w*sw.weight);
            qe.x+=(ufbx_real)(vqe.x*sw.weight);qe.y+=(ufbx_real)(vqe.y*sw.weight);qe.z+=(ufbx_real)(vqe.z*sw.weight);qe.w+=(ufbx_real)(vqe.w*sw.weight);
            qs.x+=(ufbx_real)(t.scale.x*sw.weight);qs.y+=(ufbx_real)(t.scale.y*sw.weight);qs.z+=(ufbx_real)(t.scale.z*sw.weight);
        }
        if(sv.dq_weight<1.0f) for(int k=0;k<12;k++) linear.v[k]+=(ufbx_real)(matrix.v[k]*(1.0-sv.dq_weight)*sw.weight);
    }
    if(total<=0) return *fallback;
    if(fabs(total-1.0)>1e-6) {
        double inv=1.0/total;
        if(sv.dq_weight>0.0f) { q0.x*=inv;q0.y*=inv;q0.z*=inv;q0.w*=inv;qe.x*=inv;qe.y*=inv;qe.z*=inv;qe.w*=inv;qs.x*=inv;qs.y*=inv;qs.z*=inv; }
        if(sv.dq_weight<1.0f) for(int k=0;k<12;k++) linear.v[k]*=(ufbx_real)inv;
    }
    if(sv.dq_weight>0.0f) {
        ufbx_transform dqt={0};double len=sqrt((double)q0.x*q0.x+(double)q0.y*q0.y+(double)q0.z*q0.z+(double)q0.w*q0.w);
        if(len<=1e-12) { if(unsupported) *unsupported=true; return *fallback; }
        double rcp=1.0/len,rcp2=2.0*rcp*rcp;dqt.rotation.x=(ufbx_real)(q0.x*rcp);dqt.rotation.y=(ufbx_real)(q0.y*rcp);dqt.rotation.z=(ufbx_real)(q0.z*rcp);dqt.rotation.w=(ufbx_real)(q0.w*rcp);
        dqt.scale=qs;dqt.translation.x=(ufbx_real)(rcp2*(-qe.w*q0.x+qe.x*q0.w-qe.y*q0.z+qe.z*q0.y));dqt.translation.y=(ufbx_real)(rcp2*(-qe.w*q0.y+qe.x*q0.z+qe.y*q0.w-qe.z*q0.x));dqt.translation.z=(ufbx_real)(rcp2*(-qe.w*q0.z-qe.x*q0.y+qe.y*q0.x+qe.z*q0.w));
        ufbx_matrix dqm=ufbx_transform_to_matrix(&dqt);
        if(sv.dq_weight<1.0f) for(int k=0;k<12;k++) linear.v[k]+=(ufbx_real)(dqm.v[k]*sv.dq_weight); else linear=dqm;
    }
    return linear;
}

int ysm_fbx_geometry_write(float *positions,size_t positions_n,float *normals,size_t normals_n,
                           float *tangents,size_t tangents_n,float *bitangents,size_t bitangents_n,
                           int *draw_nodes,int *draw_meshes,size_t draw_n,
                           float *local,float *world,float *geometry_local,float *geometry_world,size_t node_n,
                           int follow_mesh,const float *physics,size_t physics_nodes) {
    if(!evaluated_scene) return error("FBX evaluated scene unavailable");
    int expected_draws=0,expected_nodes=0;size_t p=0,n=0,t=0,b=0;
    if(geometry_counts_for(source_scene,&expected_draws,&p,&n,&t,&b,&expected_nodes)<0) return -1;
    if(draw_n<(size_t)expected_draws || node_n<(size_t)expected_nodes || positions_n<p || normals_n<n || tangents_n<t || bitangents_n<b)
        return error("FBX typed geometry output buffer is too small");
    size_t po=0,no=0,to=0,bo=0,di=0;
    for(size_t ni=0;ni<evaluated_scene->nodes.count&&!failed;ni++) {
        ufbx_node *node=evaluated_scene->nodes.data[ni];
        if(typed_matrix(local,node_n*12,&(size_t){ni*12},node->node_to_parent)<0 ||
           typed_matrix(world,node_n*12,&(size_t){ni*12},node->node_to_world)<0 ||
           typed_matrix(geometry_local,node_n*12,&(size_t){ni*12},node->geometry_to_node)<0 ||
           typed_matrix(geometry_world,node_n*12,&(size_t){ni*12},node->geometry_to_world)<0) return -1;
        ufbx_mesh *m=node->mesh;if(!m) continue;
        if(!skin_semantics_supported) return error("FBX multiple non-equivalent skin deformers require explicit source/profile selection");
        if(m->cache_deformers.count || m->subdivision_render_levels) return error("FBX geometry requires an unsupported deformer");
        size_t vn=m->num_vertices;
        if(vn>memory_limit/(sizeof(ufbx_vec3)*2+sizeof(ufbx_matrix))) return error("FBX typed deformation memory budget exceeded");
        ufbx_vec3 *base=vn?malloc(vn*sizeof(ufbx_vec3)):NULL;
        ufbx_vec3 *normal_offsets=vn?calloc(vn,sizeof(ufbx_vec3)):NULL;
        ufbx_matrix *matrices=vn?malloc(vn*sizeof(ufbx_matrix)):NULL;
        if(vn&&(!base||!normal_offsets||!matrices)) { free(base);free(normal_offsets);free(matrices);return error("FBX typed deformation memory exhausted"); }
        if(vn) memcpy(base,m->vertices.data,vn*sizeof(ufbx_vec3));
        for(size_t blend=0;blend<m->blend_deformers.count;blend++) {
            ufbx_blend_deformer *d=m->blend_deformers.data[blend];ufbx_add_blend_vertex_offsets(d,base,vn,1.0);
            for(size_t ci=0;ci<d->channels.count;ci++) for(size_t ki=0;ki<d->channels.data[ci]->keyframes.count;ki++) {
                ufbx_blend_keyframe *key=&d->channels.data[ci]->keyframes.data[ki];ufbx_blend_shape *shape=key->shape;
                for(size_t j=0;j<shape->normal_offsets.count;j++) { size_t v=shape->offset_vertices.data[j];if(v>=vn) continue;
                    double w=key->effective_weight*(j<shape->offset_weights.count?shape->offset_weights.data[j]:1.0);
                    for(int k=0;k<3;k++) normal_offsets[v].v[k]+=shape->normal_offsets.data[j].v[k]*w;
                }
            }
        }
        ufbx_skin_deformer *skin=m->skin_deformers.count?m->skin_deformers.data[0]:NULL;ufbx_matrix mesh_correction=ufbx_identity_matrix;
        if(physics&&physics_nodes&&skin&&(skin->skinning_method==UFBX_SKINNING_METHOD_DUAL_QUATERNION||skin->skinning_method==UFBX_SKINNING_METHOD_BLENDED_DQ_LINEAR)) {
            /* Check each cluster once per mesh. Doing this inside every vertex
               influence would repeat matrix decomposition for large meshes. */
            for(size_t ci=0;ci<skin->clusters.count;ci++) { ufbx_matrix cluster_matrix=physics_cluster_matrix(evaluated_scene,skin->clusters.data[ci],physics,physics_nodes);if(!physics_matrix_is_rigid_transform(&cluster_matrix)) return 1; }
        }
        if(skin&&follow_mesh) {
            ufbx_bone_pose *bind=ufbx_get_bone_pose(node->bind_pose,node);for(size_t j=0;!bind&&j<m->instances.count;j++) bind=ufbx_get_bone_pose(m->instances.data[j]->bind_pose,m->instances.data[j]);
            if(!bind||ufbx_matrix_determinant(&bind->bone_to_world)==0) { free(base);free(normal_offsets);free(matrices);return error("FBX mesh-motion compensation has no valid bind pose"); }
            ufbx_matrix inverse_bind=ufbx_matrix_invert(&bind->bone_to_world);
            mesh_correction=ufbx_matrix_mul(&node->geometry_to_world,&inverse_bind);
        }
        bool unsupported=false;
        for(size_t v=0;v<vn;v++) {
            matrices[v]=skin?(physics&&physics_nodes?physics_skin_vertex_matrix(evaluated_scene,skin,v,physics,physics_nodes,&node->geometry_to_world,&unsupported):ufbx_get_skin_vertex_matrix(skin,v,&node->geometry_to_world)):node->geometry_to_world;
            if(unsupported) { free(base);free(normal_offsets);free(matrices);return 1; }
            if(!skin&&physics&&physics_nodes) { int node_index=physics_node_index(evaluated_scene,node);if(node_index>=0&&(size_t)node_index<physics_nodes) { ufbx_matrix delta=physics_delta_matrix(physics+(size_t)node_index*16);matrices[v]=ufbx_matrix_mul(&delta,&matrices[v]); } }
            if(skin&&follow_mesh) { ufbx_skin_vertex sv=skin->vertices.data[v];double weight=0;for(size_t j=0;j<sv.num_weights;j++) weight+=skin->weights.data[sv.weight_begin+j].weight;if(weight>0) matrices[v]=ufbx_matrix_mul(&mesh_correction,&matrices[v]); }
            base[v]=ufbx_transform_position(&matrices[v],base[v]); }
        draw_nodes[di]=(int)ni;draw_meshes[di++]=(int)m->typed_id;
        for(size_t j=0;j<m->num_indices;j++) { size_t v=m->vertex_indices.data[j];if(typed_vec3(positions,positions_n,&po,base[v])<0) break;
            if(m->vertex_normal.exists) { ufbx_vec3 normal=ufbx_get_vertex_vec3(&m->vertex_normal,j);for(int k=0;k<3;k++) normal.v[k]+=normal_offsets[v].v[k];normal=normal_transform(&matrices[v],normal);if(typed_vec3(normals,normals_n,&no,normal)<0) break; }
        }
        /* Keep each UV set contiguous, matching the source record layout. */
        for(size_t ui=0;ui<m->uv_sets.count;ui++) { ufbx_uv_set *uv=&m->uv_sets.data[ui];
            if(uv->vertex_tangent.exists) for(size_t j=0;j<m->num_indices;j++) { size_t v=m->vertex_indices.data[j];ufbx_vec3 x=ufbx_vec3_normalize(ufbx_transform_direction(&matrices[v],ufbx_get_vertex_vec3(&uv->vertex_tangent,j)));if(typed_vec3(tangents,tangents_n,&to,x)<0) break; }
            if(uv->vertex_bitangent.exists) for(size_t j=0;j<m->num_indices;j++) { size_t v=m->vertex_indices.data[j];ufbx_vec3 x=ufbx_vec3_normalize(ufbx_transform_direction(&matrices[v],ufbx_get_vertex_vec3(&uv->vertex_bitangent,j)));if(typed_vec3(bitangents,bitangents_n,&bo,x)<0) break; }
        }
        free(base);free(normal_offsets);free(matrices);
    }
    return failed?-1:0;
}
