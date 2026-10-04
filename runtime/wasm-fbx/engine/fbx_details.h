/* Private snapshot helpers. Included by fbx_bridge.c, not an independent translation unit. */
static void transform(ufbx_transform t) {
    text("{\"translation\":");vector(t.translation.v,3);text(",\"rotation\":");vector(t.rotation.v,4);text(",\"scale\":");vector(t.scale.v,3);text("}");
}
static int node_id(ufbx_node *n) { return n?(int)n->typed_id:-1; }
static void shapes(ufbx_scene *s,bool source) {
    text(",\"blendDeformers\":[");for(size_t i=0;i<s->blend_deformers.count;i++) {
        ufbx_blend_deformer *d=s->blend_deformers.data[i];if(i) text(",");format("{\"element\":%u,\"channels\":[",d->element_id);
        for(size_t j=0;j<d->channels.count;j++) { if(j) text(",");format("%u",d->channels.data[j]->typed_id); }text("]}");
    }text("],\"blendChannels\":[");for(size_t i=0;i<s->blend_channels.count;i++) {
        ufbx_blend_channel *c=s->blend_channels.data[i];if(i) text(",");format("{\"element\":%u,\"weight\":",c->element_id);number(c->weight);text(",\"keys\":[");
        for(size_t j=0;j<c->keyframes.count;j++) { ufbx_blend_keyframe *k=&c->keyframes.data[j];if(j) text(",");format("[%u,",k->shape->typed_id);number(k->target_weight);text(",");number(k->effective_weight);text("]"); }text("]}");
    }text("]");if(!source) return;
    text(",\"blendShapes\":[");for(size_t i=0;i<s->blend_shapes.count;i++) {
        ufbx_blend_shape *b=s->blend_shapes.data[i];if(i) text(",");format("{\"element\":%u,\"vertices\":",b->element_id);ints(b->offset_vertices);
        text(",\"positions\":[");for(size_t j=0;j<b->position_offsets.count;j++) { if(j) text(",");vector(b->position_offsets.data[j].v,3); }
        text("],\"normals\":[");for(size_t j=0;j<b->normal_offsets.count;j++) { if(j) text(",");vector(b->normal_offsets.data[j].v,3); }
        text("],\"weights\":");vector(b->offset_weights.data,b->offset_weights.count);text("}");
    }text("]");
}
static void material_map(const char *name,ufbx_material_map m,bool *first) {
    if(!*first) text(",");*first=false;format("\"%s\":{\"value\":",name);vector(m.value_vec4.v,4);
    format(",\"integer\":\"%lld\",\"texture\":%d,\"hasValue\":%s,\"textureEnabled\":%s,\"disabled\":%s,\"components\":%u}",
        (long long)m.value_int,m.texture?(int)m.texture->typed_id:-1,m.has_value?"true":"false",m.texture_enabled?"true":"false",m.feature_disabled?"true":"false",(unsigned)m.value_components);
}
static void material_feature(const char *name,ufbx_material_feature_info f,bool *first) {
    if(!*first) text(",");*first=false;format("\"%s\":[%s,%s]",name,f.enabled?"true":"false",f.is_explicit?"true":"false");
}
static void materials(ufbx_scene *s) {
    text(",\"materials\":[");for(size_t i=0;i<s->materials.count;i++) {
        ufbx_material *m=s->materials.data[i];if(i) text(",");format("{\"element\":%u,\"shaderType\":%u,\"shader\":%d,\"shadingModel\":",m->element_id,(unsigned)m->shader_type,element_id(m->shader?&m->shader->element:NULL));string(m->shading_model_name);
        text(",\"shaderPrefix\":");string(m->shader_prop_prefix);bool first=true;
        text(",\"fbx\":{");
#define FBX_MAP(name) material_map(#name,m->fbx.name,&first);
#define PBR_MAP(name) material_map(#name,m->pbr.name,&first);
#define FEATURE(name) material_feature(#name,m->features.name,&first);
#include "fbx_material_fields.h"
#undef FBX_MAP
#undef PBR_MAP
#undef FEATURE
        text("},\"textures\":[");for(size_t j=0;j<m->textures.count;j++) { ufbx_material_texture *t=&m->textures.data[j];if(j) text(",");text("{\"property\":");string(t->material_prop);text(",\"shaderProperty\":");string(t->shader_prop);format(",\"texture\":%u}",t->texture->typed_id); }text("]}");
    }text("]");
}
static void lights_cameras(ufbx_scene *s) {
    text(",\"lights\":[");for(size_t i=0;i<s->lights.count;i++) {
        ufbx_light *l=s->lights.data[i];if(i) text(",");format("{\"element\":%u,\"type\":%u,\"decay\":%u,\"areaShape\":%u,\"castLight\":%s,\"castShadows\":%s,\"color\":",l->element_id,(unsigned)l->type,(unsigned)l->decay,(unsigned)l->area_shape,l->cast_light?"true":"false",l->cast_shadows?"true":"false");
        vector(l->color.v,3);text(",\"intensity\":");number(l->intensity);text(",\"direction\":");vector(l->local_direction.v,3);
        text(",\"innerAngle\":");number(l->inner_angle);text(",\"outerAngle\":");number(l->outer_angle);text("}");
    }text("],\"cameras\":[");for(size_t i=0;i<s->cameras.count;i++) {
        ufbx_camera *c=s->cameras.data[i];if(i) text(",");format("{\"element\":%u,\"projection\":%u,\"resolutionPixels\":%s,\"axes\":[%u,%u,%u],\"modes\":[%u,%u,%u,%u],\"parameters\":{",
            c->element_id,(unsigned)c->projection_mode,c->resolution_is_pixels?"true":"false",(unsigned)c->projection_axes.right,(unsigned)c->projection_axes.up,(unsigned)c->projection_axes.front,
            (unsigned)c->aspect_mode,(unsigned)c->aperture_mode,(unsigned)c->gate_fit,(unsigned)c->aperture_format);
#define CP2(name) text("\"" #name "\":");vector(c->name.v,2);text(",");
#define CP1(name) text("\"" #name "\":[");number(c->name);text("],");
        CP2(resolution) CP2(field_of_view_deg) CP2(field_of_view_tan) CP2(orthographic_size) CP2(projection_plane)
        CP2(film_size_inch) CP2(aperture_size_inch) CP1(orthographic_extent) CP1(aspect_ratio) CP1(near_plane) CP1(far_plane) CP1(focal_length_mm)
#undef CP1
#undef CP2
        text("\"squeeze_ratio\":[");number(c->squeeze_ratio);text("]}}");
    }text("]");
}
static void constraints(ufbx_scene *s) {
    text(",\"constraints\":[");for(size_t i=0;i<s->constraints.count;i++) {
        ufbx_constraint *c=s->constraints.data[i];if(i) text(",");format("{\"element\":%u,\"type\":%u,\"node\":%d,\"typeName\":",c->element_id,(unsigned)c->type,node_id(c->node));string(c->type_name);
        format(",\"active\":%s,\"weight\":",c->active?"true":"false");number(c->weight);text(",\"axes\":[");
        for(int k=0;k<9;k++) { if(k) text(",");bool v=k<3?c->constrain_translation[k]:k<6?c->constrain_rotation[k-3]:c->constrain_scale[k-6];text(v?"1":"0"); }
        text("],\"offset\":");transform(c->transform_offset);text(",\"targets\":[");
        for(size_t j=0;j<c->targets.count;j++) { ufbx_constraint_target *t=&c->targets.data[j];if(j) text(",");format("{\"node\":%d,\"weight\":",node_id(t->node));number(t->weight);text(",\"transform\":");transform(t->transform);text("}"); }
        text("],\"aim\":");vector(c->aim_vector.v,3);text(",\"upVector\":");vector(c->aim_up_vector.v,3);text(",\"pole\":");vector(c->ik_pole_vector.v,3);
        format(",\"upType\":%u,\"upNode\":%d,\"effector\":%d,\"end\":%d}",(unsigned)c->aim_up_type,node_id(c->aim_up_node),node_id(c->ik_effector),node_id(c->ik_end_node));
    }text("]");
}
static void warnings(ufbx_scene *s) {
    text(",\"warnings\":[");for(size_t i=0;i<s->metadata.warnings.count;i++) { ufbx_warning *w=&s->metadata.warnings.data[i];if(i) text(",");
        format("{\"type\":%u,\"element\":%d,\"count\":%u,\"description\":",(unsigned)w->type,w->element_id==UFBX_NO_INDEX?-1:(int)w->element_id,(unsigned)w->count);string(w->description);text("}"); }text("]");
}
