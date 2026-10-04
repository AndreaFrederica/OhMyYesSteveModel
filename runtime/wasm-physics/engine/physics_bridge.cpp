// YSM headless Bullet bridge. Bullet is separately licensed under the zlib license.
// ABI 4 uses caller-owned little-endian float/int arrays. One isolated world per reactor.
#include <btBulletDynamicsCommon.h>
#include <BulletSoftBody/btSoftBodyRigidBodyCollisionConfiguration.h>
#include <BulletSoftBody/btSoftRigidDynamicsWorld.h>
#include <BulletSoftBody/btSoftBody.h>
#include <BulletDynamics/ConstraintSolver/btGeneric6DofSpringConstraint.h>
#include <BulletDynamics/ConstraintSolver/btConeTwistConstraint.h>
#include <BulletDynamics/ConstraintSolver/btSliderConstraint.h>
#include <vector>
#include <set>
#include <utility>
#include <cmath>
#include <cstdint>

namespace {
struct KinematicPairFilter : btOverlapFilterCallback {
    bool needBroadphaseCollision(btBroadphaseProxy* a,btBroadphaseProxy* b) const override {
        if (!(a->m_collisionFilterGroup & b->m_collisionFilterMask) || !(b->m_collisionFilterGroup & a->m_collisionFilterMask)) return false;
        auto* x=static_cast<btCollisionObject*>(a->m_clientObject);
        auto* y=static_cast<btCollisionObject*>(b->m_clientObject);
        return !x || !y || x->isKinematicObject()==y->isKinematicObject();
    }
};
struct State {
    struct PinTarget { int soft; int vertex; btVector3 position; };
    btSoftBodyRigidBodyCollisionConfiguration config;
    btCollisionDispatcher dispatcher{&config};
    KinematicPairFilter kinematicFilter;
    btDbvtBroadphase broadphase;
    btSequentialImpulseConstraintSolver solver;
    btSoftRigidDynamicsWorld world{&dispatcher, &broadphase, &solver, &config};
    std::vector<btCollisionShape*> shapes;
    std::vector<btRigidBody*> bodies;
    struct BodySpec { int motion; btScalar mass; btVector3 inertia; int group; int mask; };
    std::vector<BodySpec> bodySpecs;
    std::vector<btTypedConstraint*> joints;
    std::vector<btSoftBody*> softBodies;
    std::vector<PinTarget> pinTargets;
    btScalar dt = 1.0f/60;
    ~State() {
        for (auto* joint : joints) { world.removeConstraint(joint); delete joint; }
        for (auto* body : softBodies) { world.removeSoftBody(body); delete body; }
        for (auto* body : bodies) { world.removeRigidBody(body); delete body->getMotionState(); delete body; }
        for (auto* shape : shapes) delete shape;
    }
};
State* state = nullptr;
btVector3 vec(const float* p) { return {p[0],p[1],p[2]}; }
btTransform pose(const float* p) { return btTransform(btQuaternion(p[3],p[4],p[5],p[6]), vec(p)); }
bool finite(const float* p, int n) { for (int i=0;i<n;i++) if (!std::isfinite(p[i])) return false; return true; }
bool bodyValid(int id) { return state && id >= 0 && static_cast<size_t>(id) < state->bodies.size(); }
bool softValid(int id) { return state && id >= 0 && static_cast<size_t>(id) < state->softBodies.size(); }
void putVec(float* p,const btVector3& v) { p[0]=v.x();p[1]=v.y();p[2]=v.z(); }
}

extern "C" {
int ysm_physics_abi() { return 4; }
int ysm_physics_create(const float* cfg) {
    if (state || !finite(cfg,6) || cfg[3]<=0 || cfg[3]>1 || cfg[4]<1 || cfg[4]>1000 || (cfg[5]!=0 && cfg[5]!=1)) return -1;
    state = new State();
    state->dt=cfg[3];
    state->world.setGravity(vec(cfg));
    auto& info=state->world.getWorldInfo();
    info.m_gravity=vec(cfg); info.m_sparsesdf.Initialize();
    state->world.getSolverInfo().m_numIterations=static_cast<int>(cfg[4]);
    state->solver.setRandSeed(0);
    if(cfg[5]==1) state->world.getPairCache()->setOverlapFilterCallback(&state->kinematicFilter);
    return 0;
}
void ysm_physics_destroy() { delete state; state=nullptr; }

// shape,dimensions(3),pose(7),motion,mass,damp(2),restitution,friction,margin,group,mask
int ysm_physics_body(const float* p) {
    if (!state || !finite(p,20) || state->bodies.size()>=65536) return -1;
    auto* shape = static_cast<btCollisionShape*>(nullptr);
    switch (static_cast<int>(p[0])) {
        case 0: shape=new btSphereShape(p[1]); break;
        case 1: shape=new btBoxShape(vec(p+1)); break;
        case 2: shape=new btCapsuleShape(p[1],p[2]); break;
        default: return -1;
    }
    // Sphere/capsule radius is represented by Bullet's margin. setMargin has shape-specific semantics.
    if (static_cast<int>(p[0])==1) shape->setMargin(p[17]);
    auto transform=pose(p+4);
    auto* motionState=new btDefaultMotionState(transform);
    btVector3 inertia(0,0,0);
    if (p[12]>0) shape->calculateLocalInertia(p[12],inertia);
    btRigidBody::btRigidBodyConstructionInfo info(p[12],motionState,shape,inertia);
    info.m_linearDamping=p[13]; info.m_angularDamping=p[14];
    info.m_restitution=p[15]; info.m_friction=p[16];
    auto* body=new btRigidBody(info);
    if (static_cast<int>(p[11])==1) {
        body->setCollisionFlags(body->getCollisionFlags() | btCollisionObject::CF_KINEMATIC_OBJECT);
        body->setActivationState(DISABLE_DEACTIVATION);
    }
    state->world.addRigidBody(body,static_cast<int>(p[18]),static_cast<int>(p[19]));
    state->shapes.push_back(shape); state->bodies.push_back(body);
    state->bodySpecs.push_back({static_cast<int>(p[11]),p[12],inertia,static_cast<int>(p[18]),static_cast<int>(p[19])});
    return static_cast<int>(state->bodies.size()-1);
}

// type,A,B,frameA(7),frameB(7),linearLower(3),linearUpper(3),angularLower(3),
// angularUpper(3),linearStiffness(3),angularStiffness(3),damping,disableCollision
int ysm_physics_joint(const float* p) {
    if (!state || !finite(p,37)) return -1;
    int a=static_cast<int>(p[1]),b=static_cast<int>(p[2]),type=static_cast<int>(p[0]);
    if ((a!=-1 && !bodyValid(a)) || (b!=-1 && !bodyValid(b)) || a==b) return -1;
    auto& A=a==-1 ? btTypedConstraint::getFixedBody() : *state->bodies[a];
    auto& B=b==-1 ? btTypedConstraint::getFixedBody() : *state->bodies[b];
    auto fa=pose(p+3),fb=pose(p+10);
    btTypedConstraint* joint=nullptr;
    switch (type) {
        case 0: case 1: {
            auto* c=type==0 ? static_cast<btGeneric6DofConstraint*>(new btGeneric6DofSpringConstraint(A,B,fa,fb,true))
                           : new btGeneric6DofConstraint(A,B,fa,fb,true);
            c->setLinearLowerLimit(vec(p+17)); c->setLinearUpperLimit(vec(p+20));
            c->setAngularLowerLimit(vec(p+23)); c->setAngularUpperLimit(vec(p+26));
            if (type==0) {
                auto* spring=static_cast<btGeneric6DofSpringConstraint*>(c);
                for (int axis=0;axis<6;axis++) {
                    spring->setStiffness(axis,p[29+axis]);
                    spring->setDamping(axis,p[35]);
                    spring->enableSpring(axis,p[29+axis]!=0);
                }
                spring->setEquilibriumPoint();
            }
            joint=c; break;
        }
        case 2: joint=new btPoint2PointConstraint(A,B,fa.getOrigin(),fb.getOrigin()); break;
        case 3: {
            auto* c=new btConeTwistConstraint(A,B,fa,fb);
            c->setLimit(p[26],p[27],p[28]); joint=c; break;
        }
        case 4: {
            auto* c=new btSliderConstraint(A,B,fa,fb,true);
            c->setLowerLinLimit(p[17]); c->setUpperLinLimit(p[20]);
            c->setLowerAngLimit(p[23]); c->setUpperAngLimit(p[26]); joint=c; break;
        }
        case 5: {
            auto* c=new btHingeConstraint(A,B,fa,fb,true);
            c->setLimit(p[23],p[26]); joint=c; break;
        }
        default: return -1;
    }
    state->world.addConstraint(joint,p[36]!=0); state->joints.push_back(joint);
    return static_cast<int>(state->joints.size()-1);
}

int ysm_physics_configure_joint(int id,const float* p) {
    if (!state || id<0 || static_cast<size_t>(id)>=state->joints.size() || !finite(p,15)) return -1;
    auto* joint=state->joints[id];
    switch (static_cast<int>(p[0])) {
        case 0: {
            if (joint->getConstraintType()!=D6_CONSTRAINT_TYPE && joint->getConstraintType()!=D6_SPRING_CONSTRAINT_TYPE) return -1;
            for(int axis=0;axis<6;axis++) if(p[axis+1]<0 || p[axis+1]>1 || p[axis+7]<0) return -1;
            auto* c=static_cast<btGeneric6DofConstraint*>(joint);
            for(int axis=0;axis<6;axis++){c->setParam(BT_CONSTRAINT_STOP_ERP,p[axis+1],axis);c->setParam(BT_CONSTRAINT_STOP_CFM,p[axis+7],axis);}
            break;
        }
        case 3: {
            if (joint->getConstraintType()!=CONETWIST_CONSTRAINT_TYPE) return -1;
            auto* c=static_cast<btConeTwistConstraint*>(joint);
            c->setLimit(p[1],p[2],p[3],p[4],p[5],p[6]);c->setDamping(p[7]);c->setFixThresh(p[8]);
            c->enableMotor(p[9]!=0);c->setMaxMotorImpulse(p[10]);c->setMotorTarget(btQuaternion(p[11],p[12],p[13],p[14]));break;
        }
        case 4: {
            if (joint->getConstraintType()!=SLIDER_CONSTRAINT_TYPE) return -1;
            auto* c=static_cast<btSliderConstraint*>(joint);
            c->setPoweredLinMotor(p[1]!=0);c->setTargetLinMotorVelocity(p[2]);c->setMaxLinMotorForce(p[3]);
            c->setPoweredAngMotor(p[4]!=0);c->setTargetAngMotorVelocity(p[5]);c->setMaxAngMotorForce(p[6]);break;
        }
        case 5: {
            if (joint->getConstraintType()!=HINGE_CONSTRAINT_TYPE) return -1;
            auto* c=static_cast<btHingeConstraint*>(joint);
            c->setLimit(c->getLowerLimit(),c->getUpperLimit(),p[1],p[2],p[3]);c->enableAngularMotor(p[4]!=0,p[5],p[6]);break;
        }
        default: return -1;
    }
    return 0;
}

int ysm_physics_set_pose(int id,const float* p) {
    if (!bodyValid(id) || !finite(p,7)) return -1;
    auto* body=state->bodies[id]; auto transform=pose(p);
    // Keep the previous interpolation transform for kinematic velocity calculation on the next step.
    if (!body->isKinematicObject()) body->setInterpolationWorldTransform(transform);
    body->setWorldTransform(transform); body->getMotionState()->setWorldTransform(transform);
    state->world.updateSingleAabb(body); body->activate(true); return 0;
}
int ysm_physics_velocity(int id,const float* p) {
    if (!bodyValid(id) || !finite(p,6)) return -1;
    state->bodies[id]->setLinearVelocity(vec(p)); state->bodies[id]->setAngularVelocity(vec(p+3));
    state->bodies[id]->activate(true); return 0;
}
int ysm_physics_impulse(int id,const float* p,int local) {
    if (!bodyValid(id) || !finite(p,6)) return -1;
    auto* body=state->bodies[id]; auto linear=vec(p),angular=vec(p+3);
    if (local) { const auto& basis=body->getWorldTransform().getBasis(); linear=basis*linear; angular=basis*angular; }
    body->applyCentralImpulse(linear); body->applyTorqueImpulse(angular); body->activate(true); return 0;
}
int ysm_physics_reset_forces(int id) {
    if (!bodyValid(id)) return -1;
    auto* body=state->bodies[id];body->clearForces();body->setLinearVelocity(btVector3(0,0,0));body->setAngularVelocity(btVector3(0,0,0));
    body->setInterpolationLinearVelocity(btVector3(0,0,0));body->setInterpolationAngularVelocity(btVector3(0,0,0));body->activate(true);return 0;
}
int ysm_physics_set_kinematic(int id,int enabled) {
    if (!bodyValid(id) || state->bodySpecs[id].motion!=2) return -1;
    auto* body=state->bodies[id];if (body->isKinematicObject()==(enabled!=0)) return 0;
    const auto& spec=state->bodySpecs[id];state->world.removeRigidBody(body);
    body->setMassProps(enabled?0:spec.mass,enabled?btVector3(0,0,0):spec.inertia);
    int flags=body->getCollisionFlags() & ~(btCollisionObject::CF_STATIC_OBJECT|btCollisionObject::CF_KINEMATIC_OBJECT);
    body->setCollisionFlags(enabled?flags|btCollisionObject::CF_KINEMATIC_OBJECT:flags);body->updateInertiaTensor();
    ysm_physics_reset_forces(id);body->setInterpolationWorldTransform(body->getWorldTransform());
    body->forceActivationState(enabled?DISABLE_DEACTIVATION:ACTIVE_TAG);
    state->world.addRigidBody(body,spec.group,spec.mask);return 0;
}
int ysm_physics_set_gravity(const float* p) {
    if (!state || !finite(p,3)) return -1;state->world.setGravity(vec(p));state->world.getWorldInfo().m_gravity=vec(p);
    for(auto* body:state->bodies) body->activate(true);for(auto* body:state->softBodies) body->activate(true);return 0;
}
int ysm_physics_step() {
    if (!state) return -1;
    for (const auto& p:state->pinTargets) {
        auto& node=state->softBodies[p.soft]->m_nodes[p.vertex];node.m_v=(p.position-node.m_x)/state->dt;
    }
    // The host owns the fixed-step clock; no second accumulator or extra render-driven substep.
    state->world.stepSimulation(state->dt,0,state->dt);
    for (const auto& p:state->pinTargets) state->softBodies[p.soft]->m_nodes[p.vertex].m_v=btVector3(0,0,0);
    state->pinTargets.clear();
    for (const auto* b:state->bodies) {
        if (!finite(b->getWorldTransform().getOrigin(),3) || !finite(b->getLinearVelocity(),3) || !finite(b->getAngularVelocity(),3)) return -2;
    }
    for (const auto* b:state->softBodies) for (int i=0;i<b->m_nodes.size();i++) {
        const auto& n=b->m_nodes[i];if (!finite(n.m_x,3) || !finite(n.m_v,3)) return -2;
    }
    state->world.getWorldInfo().m_sparsesdf.GarbageCollect(); return 0;
}
int ysm_physics_body_count() { return state ? static_cast<int>(state->bodies.size()) : -1; }
int ysm_physics_read_bodies(float* out) {
    if (!state) return -1;
    for (auto* b:state->bodies) {
        const auto& t=b->getWorldTransform(); putVec(out,t.getOrigin());
        const auto q=t.getRotation(); out[3]=q.x();out[4]=q.y();out[5]=q.z();out[6]=q.w();
        putVec(out+7,b->getLinearVelocity()); putVec(out+10,b->getAngularVelocity());out+=13;
    }
    return 0;
}

// soft config: mass,margin,group,mask + the SoftConfig fields in declared order.
int ysm_physics_soft(const float* cfg,const float* vertices,int count,const int* indices,int indexCount,
                     int rope,const int* pins,int pinCount) {
    if (!state || count<1 || count>1000000 || indexCount<1 || !finite(cfg,35) || !finite(vertices,count*3)) return -1;
    if (indexCount%(rope?2:3)!=0) return -1;
    for (int i=0;i<indexCount;i++) if (indices[i]<0 || indices[i]>=count) return -1;
    for (int i=0;i<pinCount;i++) if (pins[i]<0 || pins[i]>=count) return -1;
    std::vector<btVector3> positions; positions.reserve(count);
    for (int i=0;i<count;i++) positions.push_back(vec(vertices+3*i));
    auto* body=new btSoftBody(&state->world.getWorldInfo(),count,positions.data(),nullptr);
    auto* material=body->m_materials[0];
    material->m_kLST=cfg[26];material->m_kAST=cfg[27];material->m_kVST=cfg[28];
    std::set<std::pair<int,int>> edges;
    auto link=[&](int a,int b) {
        if (a>b) std::swap(a,b);
        if (a!=b && edges.emplace(a,b).second) body->appendLink(a,b,material);
    };
    for (int i=0;i<indexCount;i+=(rope?2:3)) {
        int a=indices[i],b=indices[i+1];link(a,b);
        if (!rope) { int c=indices[i+2];link(b,c);link(c,a);body->appendFace(a,b,c,material); }
    }
    auto& c=body->m_cfg;
    c.kVCF=cfg[4];c.kDP=cfg[5];c.kDG=cfg[6];c.kLF=cfg[7];c.kPR=cfg[8];c.kVC=cfg[9];
    c.kDF=cfg[10];c.kMT=cfg[11];c.kCHR=cfg[12];c.kKHR=cfg[13];c.kSHR=cfg[14];c.kAHR=cfg[15];
    c.kSRHR_CL=cfg[16];c.kSKHR_CL=cfg[17];c.kSSHR_CL=cfg[18];
    c.kSR_SPLT_CL=cfg[19];c.kSK_SPLT_CL=cfg[20];c.kSS_SPLT_CL=cfg[21];
    c.viterations=static_cast<int>(cfg[22]);c.piterations=static_cast<int>(cfg[23]);
    c.diterations=static_cast<int>(cfg[24]);c.citerations=static_cast<int>(cfg[25]);
    c.aeromodel=static_cast<btSoftBody::eAeroModel::_>(static_cast<int>(cfg[29]));
    c.collisions=static_cast<int>(cfg[30]);
    if (cfg[32]>0) body->generateBendingConstraints(static_cast<int>(cfg[32]),material);
    body->setTotalMass(cfg[0],false);
    for (int i=0;i<pinCount;i++) body->setMass(pins[i],0);
    body->getCollisionShape()->setMargin(cfg[1]);
    body->setPose(c.kVC>0,c.kMT>0);
    if (cfg[33]!=0) body->generateClusters(static_cast<int>(cfg[31]));
    if (cfg[34]!=0) body->randomizeConstraints();
    state->world.addSoftBody(body,static_cast<int>(cfg[2]),static_cast<int>(cfg[3]));
    state->softBodies.push_back(body); return static_cast<int>(state->softBodies.size()-1);
}
int ysm_physics_pin(int soft,int vertex,const float* p) {
    if (!softValid(soft) || vertex<0 || vertex>=state->softBodies[soft]->m_nodes.size() || !finite(p,3)) return -1;
    auto& node=state->softBodies[soft]->m_nodes[vertex];if (node.m_im!=0) return -1;
    for (auto& target:state->pinTargets) if (target.soft==soft && target.vertex==vertex) { target.position=vec(p);return 0; }
    state->pinTargets.push_back({soft,vertex,vec(p)});
    state->softBodies[soft]->activate(true);return 0;
}
int ysm_physics_read_soft_normals(int soft,float* out) {
    if (!softValid(soft)) return -1;
    auto* body=state->softBodies[soft];
    for (int i=0;i<body->m_nodes.size();i++) { putVec(out,body->m_nodes[i].m_n);out+=3; }return 0;
}
int ysm_physics_anchor(int soft,int vertex,int rigid,int disableCollision) {
    if (!softValid(soft) || !bodyValid(rigid) || vertex<0 || vertex>=state->softBodies[soft]->m_nodes.size()) return -1;
    state->softBodies[soft]->appendAnchor(vertex,state->bodies[rigid],disableCollision!=0); return 0;
}
int ysm_physics_soft_count(int soft) { return softValid(soft) ? state->softBodies[soft]->m_nodes.size() : -1; }
int ysm_physics_read_soft(int soft,float* out) {
    if (!softValid(soft)) return -1;
    const auto& nodes=state->softBodies[soft]->m_nodes;
    for (int i=0;i<nodes.size();i++) putVec(out+3*i,nodes[i].m_x);
    return 0;
}
int ysm_physics_forces(int count,const float *p) {
    if(!state || count<0 || count>65536 || !finite(p,count*8))return -1;
    for(int i=0;i<count;i++){float id=p[i*8];if(id<0 || id>65535 || std::trunc(id)!=id || !bodyValid(int(id)) || (p[i*8+7]!=0 && p[i*8+7]!=1))return -1;}
    for(int i=0;i<count;i++) {
        auto *b=state->bodies[int(p[i*8])];btVector3 linear=vec(p+i*8+1),angular=vec(p+i*8+4);
        if(p[i*8+7]!=0){linear=b->getWorldTransform().getBasis()*linear;angular=b->getWorldTransform().getBasis()*angular;}
        b->applyCentralForce(linear);b->applyTorque(angular);b->activate(true);
    }
    return 0;
}
int ysm_physics_clamp_velocities(int count,const float *p) {
    if(!state || count<0 || count>65536 || !finite(p,count+2) || p[0]<=0 || p[1]<=0)return -1;
    for(int i=0;i<count;i++){float id=p[i+2];if(id<0 || id>65535 || std::trunc(id)!=id || !bodyValid(int(id)))return -1;}
    auto limited=[](const btVector3 &v,float limit){double length=std::hypot(double(v.x()),double(v.y()),double(v.z()));if(length<=limit)return v;double scale=limit/length;return btVector3(btScalar(v.x()*scale),btScalar(v.y()*scale),btScalar(v.z()*scale));};
    for(int i=0;i<count;i++){
        auto *b=state->bodies[int(p[i+2])];if(b->isStaticOrKinematicObject())continue;
        b->setLinearVelocity(limited(b->getLinearVelocity(),p[0]));b->setAngularVelocity(limited(b->getAngularVelocity(),p[1]));
    }
    return 0;
}

}
