/* Per-instance geometry output. Source normals and every weight are retained. */
static ufbx_vec3 normal_transform(const ufbx_matrix *m,ufbx_vec3 n) {
    if(ufbx_matrix_determinant(m)==0.0) { error("FBX singular deformation cannot transform authored normals");return n; }
    ufbx_matrix inv=ufbx_matrix_invert(m);
    ufbx_vec3 out={ inv.m00*n.x+inv.m10*n.y+inv.m20*n.z,
                   inv.m01*n.x+inv.m11*n.y+inv.m21*n.z,
                   inv.m02*n.x+inv.m12*n.y+inv.m22*n.z };
    return ufbx_vec3_normalize(out);
}
static void draws(ufbx_scene *s,bool follow_mesh) {
    text(",\"draws\":[");bool first=true;
    for(size_t ni=0;ni<s->nodes.count&&!failed;ni++) {
        ufbx_node *node=s->nodes.data[ni];ufbx_mesh *m=node->mesh;if(!m) continue;
        // Reject unresolved semantics, including optional paths, before producing a plausible wrong mesh.
        if(m->skin_deformers.count>1) { error("FBX multiple skin deformers require an explicit ordered adapter");break; }
        if(m->cache_deformers.count || m->subdivision_render_levels) { error("FBX geometry cache/subdivision evaluation unavailable");break; }
        size_t n=m->num_vertices;
        if(n>memory_limit/(sizeof(ufbx_vec3)*2+sizeof(ufbx_matrix))) { error("FBX deformation memory budget exceeded");break; }
        ufbx_vec3 *positions=malloc(n*sizeof(ufbx_vec3)),*normal_offsets=calloc(n,sizeof(ufbx_vec3));
        ufbx_matrix *matrices=malloc(n*sizeof(ufbx_matrix));
        if(n&&(!positions||!normal_offsets||!matrices)) { free(positions);free(normal_offsets);free(matrices);error("FBX deformation memory exhausted");break; }
        if(n) memcpy(positions,m->vertices.data,n*sizeof(ufbx_vec3));
        for(size_t di=0;di<m->blend_deformers.count;di++) {
            ufbx_blend_deformer *d=m->blend_deformers.data[di];ufbx_add_blend_vertex_offsets(d,positions,n,1.0);
            for(size_t ci=0;ci<d->channels.count;ci++) {
                ufbx_blend_channel *c=d->channels.data[ci];for(size_t ki=0;ki<c->keyframes.count;ki++) {
                    ufbx_blend_keyframe *key=&c->keyframes.data[ki];ufbx_blend_shape *shape=key->shape;
                    for(size_t j=0;j<shape->normal_offsets.count;j++) {
                        size_t v=shape->offset_vertices.data[j];if(v>=n) continue;
                        double w=key->effective_weight*(j<shape->offset_weights.count?shape->offset_weights.data[j]:1.0);
                        for(int k=0;k<3;k++) normal_offsets[v].v[k]+=shape->normal_offsets.data[j].v[k]*w;
                    }
                }
            }
        }
        ufbx_skin_deformer *skin=m->skin_deformers.count?m->skin_deformers.data[0]:NULL;
        ufbx_matrix mesh_correction=ufbx_identity_matrix;
        if(skin&&follow_mesh) {
            ufbx_bone_pose *bind=ufbx_get_bone_pose(node->bind_pose,node);
            for(size_t j=0;!bind&&j<m->instances.count;j++) bind=ufbx_get_bone_pose(m->instances.data[j]->bind_pose,m->instances.data[j]);
            if(!bind) error("FBX mesh-motion compensation requires a mesh bind pose");
            else if(ufbx_matrix_determinant(&bind->bone_to_world)==0) error("FBX mesh bind pose is singular");
            else { ufbx_matrix inverse=ufbx_matrix_invert(&bind->bone_to_world);mesh_correction=ufbx_matrix_mul(&node->geometry_to_world,&inverse); }
        }
        if(skin) for(size_t ci=0;ci<skin->clusters.count;ci++) {
            ufbx_skin_cluster *cluster=skin->clusters.data[ci];ufbx_dom_node *dom=cluster->element.dom_node;
            ufbx_dom_node *mode=dom?ufbx_dom_find(dom,"Mode"):NULL;
            if(mode&&mode->values.count&&mode->values.data[0].value_str.length&&strcmp(mode->values.data[0].value_str.data,"Normalize")) error("FBX non-normalized cluster mode requires a dedicated adapter");
            if(skin->skinning_method==UFBX_SKINNING_METHOD_DUAL_QUATERNION || skin->skinning_method==UFBX_SKINNING_METHOD_BLENDED_DQ_LINEAR) {
                ufbx_matrix reconstructed=ufbx_transform_to_matrix(&cluster->geometry_to_world_transform);
                for(int k=0;k<12;k++) if(fabs(reconstructed.v[k]-cluster->geometry_to_world.v[k])>1e-8*(1.0+fabs(cluster->geometry_to_world.v[k]))) error("FBX DQ transform contains shear that cannot be discarded");
            }
        }
        for(size_t v=0;v<n&&!failed;v++) {
            matrices[v]=skin?ufbx_get_skin_vertex_matrix(skin,v,&node->geometry_to_world):node->geometry_to_world;
            if(skin&&follow_mesh) {
                ufbx_skin_vertex sv=skin->vertices.data[v];double weight=0;for(size_t j=0;j<sv.num_weights;j++) weight+=skin->weights.data[sv.weight_begin+j].weight;
                if(weight>0) matrices[v]=ufbx_matrix_mul(&mesh_correction,&matrices[v]);
            }
            positions[v]=ufbx_transform_position(&matrices[v],positions[v]);
        }
        if(!failed) {
            if(!first) text(",");first=false;format("{\"node\":%u,\"mesh\":%u,\"positions\":[",(unsigned)ni,m->typed_id);
            for(size_t j=0;j<m->num_indices&&!failed;j++) { if(j) text(",");vector(positions[m->vertex_indices.data[j]].v,3); }
            text("],\"normals\":[");if(m->vertex_normal.exists) for(size_t j=0;j<m->num_indices&&!failed;j++) {
                if(j) text(",");size_t v=m->vertex_indices.data[j];ufbx_vec3 normal=ufbx_get_vertex_vec3(&m->vertex_normal,j);
                for(int k=0;k<3;k++) normal.v[k]+=normal_offsets[v].v[k];normal=normal_transform(&matrices[v],normal);vector(normal.v,3);
            }
            text("],\"uvDirections\":[");for(size_t ui=0;ui<m->uv_sets.count&&!failed;ui++) {
                ufbx_uv_set *uv=&m->uv_sets.data[ui];if(ui) text(",");text("{\"tangents\":[");
                for(int attr=0;attr<2;attr++) {
                    ufbx_vertex_vec3 a=attr?uv->vertex_bitangent:uv->vertex_tangent;
                    if(a.exists) for(size_t j=0;j<m->num_indices&&!failed;j++) { if(j) text(",");ufbx_vec3 t=ufbx_get_vertex_vec3(&a,j);
                        t=ufbx_vec3_normalize(ufbx_transform_direction(&matrices[m->vertex_indices.data[j]],t));vector(t.v,3); }
                    text(attr?"]}":"],\"bitangents\":[");
                }
            }text("]}");
        }
        free(matrices);free(normal_offsets);free(positions);
    }text("]");
}
