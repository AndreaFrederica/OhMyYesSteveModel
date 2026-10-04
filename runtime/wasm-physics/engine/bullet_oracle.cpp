// Independent native reference: constructs the fixture with Bullet APIs, not the WASM bridge.
#include <btBulletDynamicsCommon.h>
#include <BulletSoftBody/btSoftBodyRigidBodyCollisionConfiguration.h>
#include <BulletSoftBody/btSoftRigidDynamicsWorld.h>
#include <cstdio>

int main() {
    btSoftBodyRigidBodyCollisionConfiguration config;
    btCollisionDispatcher dispatcher(&config);btDbvtBroadphase broadphase;
    btSequentialImpulseConstraintSolver solver;
    btSoftRigidDynamicsWorld world(&dispatcher,&broadphase,&solver,&config);
    world.setGravity(btVector3(0,-9.8f,0));world.getWorldInfo().m_gravity=world.getGravity();
    world.getWorldInfo().m_sparsesdf.Initialize();solver.setRandSeed(0);
    btBoxShape floorShape(btVector3(10,.5f,10));floorShape.setMargin(.04f);
    btSphereShape ballShape(.5f);
    btTransform floorTransform;floorTransform.setIdentity();floorTransform.setOrigin(btVector3(0,-.5f,0));
    btTransform ballTransform;ballTransform.setIdentity();ballTransform.setOrigin(btVector3(0,3,0));
    btDefaultMotionState floorMotion(floorTransform),ballMotion(ballTransform);
    btVector3 inertia;ballShape.calculateLocalInertia(1,inertia);
    btRigidBody::btRigidBodyConstructionInfo floorInfo(0,&floorMotion,&floorShape,btVector3(0,0,0));
    btRigidBody::btRigidBodyConstructionInfo ballInfo(1,&ballMotion,&ballShape,inertia);
    floorInfo.m_friction=ballInfo.m_friction=.5f;
    btRigidBody floor(floorInfo),ball(ballInfo);
    world.addRigidBody(&floor,1,0xffff);world.addRigidBody(&ball,1,0xffff);
    std::puts("# Bullet 3.25; fixed step 1/60; ground contact; step,px,py,pz,vx,vy,vz");
    for(int step=1;step<=240;step++) {
        world.stepSimulation(1.f/60,0,1.f/60);
        if(step%10==0) {
            const auto& p=ball.getWorldTransform().getOrigin();const auto& v=ball.getLinearVelocity();
            std::printf("%d,%.9g,%.9g,%.9g,%.9g,%.9g,%.9g\n",step,p.x(),p.y(),p.z(),v.x(),v.y(),v.z());
        }
    }
    world.removeRigidBody(&ball);world.removeRigidBody(&floor);
}
