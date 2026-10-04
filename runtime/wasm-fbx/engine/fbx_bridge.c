/* YSM adapter around the pinned, unmodified ufbx library. No filesystem or process access. */
#include "ufbx.h"
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <stdio.h>
#include <stdarg.h>
#include <math.h>

static ufbx_scene *source_scene, *evaluated_scene;
static size_t memory_limit, output_limit, element_limit, string_limit;
static char error_text[2048];
static char *output;
static size_t output_size, output_capacity;
static int failed;
static size_t snapshot_elements;
static bool json_string, json_escape, json_scalar;

int ysm_fbx_abi(void) { return 2; }
const char *ysm_fbx_error(void) { return error_text; }
const char *ysm_fbx_output(void) { return output; }
int ysm_fbx_output_size(void) { return failed ? -1 : (int)output_size; }
static int error(const char *message) { snprintf(error_text,sizeof(error_text),"%s",message);failed=1;return -1; }
static int ufbx_error_result(ufbx_error *value) { ufbx_format_error(error_text,sizeof(error_text),value);failed=1;return -1; }
void ysm_fbx_destroy(void) {
    ufbx_free_scene(evaluated_scene);ufbx_free_scene(source_scene);evaluated_scene=NULL;source_scene=NULL;
    free(output);output=NULL;output_size=output_capacity=0;
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
    opts.node_depth_limit=512;opts.open_file_cb.fn=no_file;opts.retain_dom=true;opts.retain_vertex_attrib_w=true;
    opts.evaluate_skinning=false;opts.generate_missing_normals=true;opts.use_blender_pbr_material=true;
    opts.target_axes=ufbx_axes_right_handed_y_up;opts.target_unit_meters=1.0;
    source_scene=ufbx_load_memory(bytes,(size_t)length,&opts,&e);
    if(!source_scene) return ufbx_error_result(&e);
    size_t count=source_scene->elements.count;
    for(size_t i=0;i<source_scene->meshes.count;i++) {
        ufbx_mesh *mesh=source_scene->meshes.data[i];
        if(mesh->num_indices>element_limit || count>element_limit-mesh->num_indices) { ysm_fbx_destroy();return error("FBX element budget exceeded"); }
        count+=mesh->num_indices;
    }
    if(count>element_limit) { ysm_fbx_destroy();return error("FBX element budget exceeded"); }
    return 0;
}
int ysm_fbx_evaluate(int stack,double seconds) {
    if(!source_scene || !isfinite(seconds) || stack< -1 || stack>=(int)source_scene->anim_stacks.count) return error("Invalid FBX animation sample");
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
        if(value && ++snapshot_elements>element_limit) { error("FBX snapshot element budget exceeded");return; }
    }
    if(length>output_limit || output_size>output_limit-length) { error("FBX snapshot output budget exceeded");return; }
    size_t needed=output_size+length+1;
    if(needed>output_capacity) {
        size_t next=output_capacity?output_capacity:4096;
        while(next<needed) { if(next>output_limit/2) { next=output_limit+1;break; }next*=2; }
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
static void number(double v) { if(!isfinite(v)) error("Non-finite FBX value");else format("%.17g",v); }
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
            vector(v->value_real_arr,4);text(",\"string\":");string(v->value_str);text(",\"blob\":");blob(v->value_blob);text("}");
        }text("]");
    }text("]");
}
static void elements(ufbx_scene *scene) {
    text("\"elements\":[");for(size_t i=0;i<scene->elements.count;i++) {
        ufbx_element *e=scene->elements.data[i];if(i) text(",");format("{\"id\":%u,\"typedId\":%u,\"type\":%u,\"name\":",e->element_id,e->typed_id,(unsigned)e->type);
        string(e->name);text(",\"properties\":");props(&e->props);text(",\"connections\":[");
        for(size_t j=0;j<e->connections_src.count;j++) {
            ufbx_connection *c=&e->connections_src.data[j];if(j) text(",");format("{\"to\":%d,\"sourceProperty\":",element_id(c->dst));string(c->src_prop);text(",\"targetProperty\":");string(c->dst_prop);text("}");
        }text("]}");
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
        ufbx_skin_deformer *s=scene->skin_deformers.data[i];if(i) text(",");format("{\"element\":%u,\"method\":%u,\"clusters\":[",s->element_id,(unsigned)s->skinning_method);
        for(size_t j=0;j<s->clusters.count;j++) { ufbx_skin_cluster *c=s->clusters.data[j];if(j) text(",");format("{\"element\":%u,\"bone\":%d,\"geometryToBone\":",c->element_id,c->bone_node?(int)c->bone_node->typed_id:-1);
            matrix(c->geometry_to_bone);text(",\"meshToBone\":");matrix(c->mesh_node_to_bone);text(",\"bindWorld\":");matrix(c->bind_to_world);
            text(",\"linkMode\":");ufbx_dom_node *mode=c->element.dom_node?ufbx_dom_find(c->element.dom_node,"Mode"):NULL;
            string(mode&&mode->values.count?mode->values.data[0].value_str:(ufbx_string){"",0});
            text(",\"rawVertices\":");ints(c->vertices);text(",\"rawWeights\":");vector(c->weights.data,c->weights.count);text("}");
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
    ufbx_scene *s=source?source_scene:evaluated_scene;failed=0;output_size=0;snapshot_elements=0;json_string=json_escape=json_scalar=false;
    format("{\"abi\":2,\"version\":%u,\"ascii\":%s,\"sourceUnitMeters\":",s->metadata.version,s->metadata.ascii?"true":"false");number(s->settings.unit_meters);
    text(",\"framesPerSecond\":");number(s->settings.frames_per_second);text(",\"creator\":");string(s->metadata.creator);
    format(",\"constraintCount\":%u,",(unsigned)s->constraints.count);elements(s);nodes(s);meshes(s,source!=0);
    shapes(s,source!=0);materials(s);lights_cameras(s);constraints(s);warnings(s);textures(s);
    if(source) { skins(s);animations(s); }
    else { draws(s,follow_mesh!=0); }
    text("}");return failed?-1:0;
}
