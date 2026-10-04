#include "physics.h"
#include <BulletDynamics/ConstraintSolver/btGeneric6DofSpringConstraint.h>
#include <BulletSoftBody/btSoftBodyHelpers.h>
#include <BulletSoftBody/btSoftBodyRigidBodyCollisionConfiguration.h>
#include <BulletSoftBody/btSoftRigidDynamicsWorld.h>
#include <algorithm>
#include <btBulletDynamicsCommon.h>
#include <cmath>
#include <memory>
#include <set>
#include <vector>

struct Body {
  std::unique_ptr<btCollisionShape> shape;
  std::unique_ptr<btMotionState> motionState;
  std::unique_ptr<btRigidBody> body;
  int motion{};
  btScalar mass{};
  btVector3 inertia{0, 0, 0};
  int group{1}, mask{0xffff};
};
struct KinematicPairFilter : btOverlapFilterCallback {
  bool needBroadphaseCollision(btBroadphaseProxy *a,
                               btBroadphaseProxy *b) const override {
    if (!(a->m_collisionFilterGroup & b->m_collisionFilterMask) ||
        !(b->m_collisionFilterGroup & a->m_collisionFilterMask))
      return false;
    auto *x = static_cast<btCollisionObject *>(a->m_clientObject);
    auto *y = static_cast<btCollisionObject *>(b->m_clientObject);
    return !x || !y || x->isKinematicObject() == y->isKinematicObject();
  }
};
struct ysm_physics_world {
  btRigidBody fixed{0, nullptr, nullptr};
  std::unique_ptr<btDefaultCollisionConfiguration> config;
  std::unique_ptr<btCollisionDispatcher> dispatcher;
  KinematicPairFilter kinematicFilter;
  std::unique_ptr<btBroadphaseInterface> broadphase;
  std::unique_ptr<btSequentialImpulseConstraintSolver> solver;
  std::unique_ptr<btSoftRigidDynamicsWorld> world;
  std::vector<Body> bodies;
  std::vector<std::unique_ptr<btTypedConstraint>> joints;
  std::vector<std::unique_ptr<btSoftBody>> soft;
  mutable bool failed{};
  std::vector<std::vector<int32_t>> pinSlots;
  size_t softVertexCount{};
  ~ysm_physics_world() {
    if (!world)
      return;
    for (auto &j : joints)
      world->removeConstraint(j.get());
    for (auto &s : soft)
      world->removeSoftBody(s.get());
    for (auto &b : bodies)
      world->removeRigidBody(b.body.get());
  }
  float step{};
  struct PinTarget {
    int soft, vertex;
    btVector3 position;
  };
  std::vector<PinTarget> pins;
};
static bool finite(const float *p, int n) {
  if (n == 0)
    return true;
  if (!p || n < 0)
    return false;
  for (int i = 0; i < n; i++)
    if (!std::isfinite(p[i]))
      return false;
  return true;
}
static bool integer(float v, int low, int high) {
  return std::isfinite(v) && v >= low && v <= high && std::trunc(v) == v;
}
static bool unit(float v) { return std::isfinite(v) && v >= 0 && v <= 1; }
static bool validPose(const float *p) {
  if (!finite(p, 7))
    return false;
  double q = double(p[3]) * p[3] + double(p[4]) * p[4] + double(p[5]) * p[5] +
             double(p[6]) * p[6];
  return q >= .999 && q <= 1.001;
}
static bool finite(const btVector3 &v) {
  return std::isfinite(v.x()) && std::isfinite(v.y()) && std::isfinite(v.z());
}
static bool validState(const btRigidBody *b) {
  auto q = b->getWorldTransform().getRotation();
  return finite(b->getWorldTransform().getOrigin()) &&
         finite(b->getLinearVelocity()) && finite(b->getAngularVelocity()) &&
         std::isfinite(q.x()) && std::isfinite(q.y()) && std::isfinite(q.z()) &&
         std::isfinite(q.w());
}
static int32_t numericFailure(const ysm_physics_world *w) {
  w->failed = true;
  return -2;
}
static btVector3 vec(const float *p) { return {p[0], p[1], p[2]}; }
static btTransform pose(const float *p) {
  btTransform t;
  t.setOrigin(vec(p));
  t.setRotation(btQuaternion(p[3], p[4], p[5], p[6]));
  return t;
}
static bool bodyValid(const ysm_physics_world *w, int i) {
  return w && i >= 0 && static_cast<size_t>(i) < w->bodies.size();
}
static bool softValid(const ysm_physics_world *w, int i) {
  return w && i >= 0 && static_cast<size_t>(i) < w->soft.size();
}
static void putVec(float *o, const btVector3 &v) {
  o[0] = v.x();
  o[1] = v.y();
  o[2] = v.z();
}
static bool validBodyConfig(const float *c, int n) {
  if (n != 20 || !finite(c, n) || !integer(c[0], 0, 2) ||
      !integer(c[11], 0, 2) || !integer(c[15], 0, 65535) ||
      !integer(c[16], 0, 65535) || !unit(c[14]) || !unit(c[17]) ||
      !unit(c[18]) || c[13] < 0 || c[19] < 0 || !validPose(c + 4))
    return false;
  if (c[1] <= 0 || (int(c[0]) == 1 && (c[2] <= 0 || c[3] <= 0)) ||
      (int(c[0]) == 2 && c[2] < 0))
    return false;
  return int(c[11]) == 2 ? c[12] > 0 : c[12] == 0;
}
static bool validJointConfig(const float *c, int n, size_t bodies) {
  if (n != 37 || !finite(c, n) || !integer(c[0], 0, 5) ||
      !integer(c[1], -1, 65535) || !integer(c[2], -1, 65535) ||
      !validPose(c + 3) || !validPose(c + 10) || c[35] < 0 ||
      !integer(c[36], 0, 1))
    return false;
  int a = int(c[1]), b = int(c[2]);
  if (a == b || (a >= 0 && size_t(a) >= bodies) ||
      (b >= 0 && size_t(b) >= bodies))
    return false;
  if (int(c[0]) == 0)
    for (int i = 29; i < 35; i++)
      if (c[i] < 0)
        return false;
  return true;
}
extern "C" int32_t ysm_physics_abi() try { return 5; } catch (...) {
  return -3;
}
extern "C" uint64_t ysm_physics_features() try { return 0xff; } catch (...) {
  return -3;
}
extern "C" int32_t ysm_physics_scalar_bits() try {
  return sizeof(btScalar) * 8;
} catch (...) {
  return -3;
}
extern "C" int32_t ysm_physics_thread_count() try { return 1; } catch (...) {
  return -3;
}
extern "C" int32_t ysm_physics_solver_iterations(ysm_physics_world *w,
                                                 int32_t iterations) try {
  if (w && w->failed)
    return -3;
  if (!w || iterations < 1 || iterations > 1000)
    return -1;
  w->world->getSolverInfo().m_numIterations = iterations;
  return 0;
} catch (...) {
  return -3;
}
extern "C" ysm_physics_world *ysm_physics_create(float gx, float gy, float gz,
                                                 float step) try {
  if (!std::isfinite(gx) || !std::isfinite(gy) || !std::isfinite(gz) ||
      !std::isfinite(step) || step <= 0 || step > 1)
    return nullptr;
  auto owner = std::make_unique<ysm_physics_world>();
  auto *w = owner.get();
  w->step = step;
  w->config = std::make_unique<btSoftBodyRigidBodyCollisionConfiguration>();
  w->dispatcher = std::make_unique<btCollisionDispatcher>(w->config.get());
  w->broadphase = std::make_unique<btDbvtBroadphase>();
  w->solver = std::make_unique<btSequentialImpulseConstraintSolver>();
  w->world = std::make_unique<btSoftRigidDynamicsWorld>(
      w->dispatcher.get(), w->broadphase.get(), w->solver.get(),
      w->config.get());
  w->world->setGravity({gx, gy, gz});
  w->world->getWorldInfo().m_gravity = {gx, gy, gz};
  w->world->getWorldInfo().m_sparsesdf.Initialize();
  w->solver->setRandSeed(0);
  return owner.release();
} catch (...) {
  return nullptr;
}
extern "C" void ysm_physics_destroy(ysm_physics_world *w) try {
  if (!w)
    return;
  delete w;
} catch (...) {
  return;
}
extern "C" int32_t ysm_physics_body(ysm_physics_world *w, const float *c,
                                    int32_t n) try {
  if (w && w->failed)
    return -3;
  if (!w || w->bodies.size() >= 65536 || !validBodyConfig(c, n))
    return -1;
  std::unique_ptr<btCollisionShape> shape;
  switch (static_cast<int>(c[0])) {
  case 0:
    if (c[1] <= 0)
      return -1;
    shape = std::make_unique<btSphereShape>(c[1]);
    break;
  case 1:
    if (c[1] <= 0 || c[2] <= 0 || c[3] <= 0)
      return -1;
    shape = std::make_unique<btBoxShape>(vec(c + 1));
    break;
  case 2:
    if (c[1] <= 0 || c[2] < 0)
      return -1;
    shape = std::make_unique<btCapsuleShape>(c[1], c[2]);
    break;
  default:
    return -1;
  }
  int motion = static_cast<int>(c[11]);
  float mass = c[12];
  if (motion < 0 || motion > 2 || mass < 0 ||
      (motion == 2 ? mass <= 0 : mass != 0))
    return -1;
  if (int(c[0]) == 1)
    shape->setMargin(c[19]);
  auto start = pose(c + 4);
  btVector3 inertia(0, 0, 0);
  if (mass > 0)
    shape->calculateLocalInertia(mass, inertia);
  auto ms = std::make_unique<btDefaultMotionState>(start);
  btRigidBody::btRigidBodyConstructionInfo info(mass, ms.get(), shape.get(),
                                                inertia);
  info.m_friction = c[13];
  info.m_restitution = c[14];
  info.m_linearDamping = c[17];
  info.m_angularDamping = c[18];
  auto body = std::make_unique<btRigidBody>(info);
  if (motion == 1) {
    body->setCollisionFlags(body->getCollisionFlags() |
                            btCollisionObject::CF_KINEMATIC_OBJECT);
    body->setActivationState(DISABLE_DEACTIVATION);
  }
  int group = static_cast<int>(c[15]), mask = static_cast<int>(c[16]);
  w->bodies.push_back({std::move(shape), std::move(ms), std::move(body), motion,
                       mass, inertia, group, mask});
  w->world->addRigidBody(w->bodies.back().body.get(), group, mask);
  return static_cast<int32_t>(w->bodies.size() - 1);
} catch (...) {
  if (w)
    w->failed = true;
  return -3;
}
extern "C" int32_t ysm_physics_set_pose(ysm_physics_world *w, int32_t i,
                                        const float *p, int32_t n) try {
  if (w && w->failed)
    return -3;
  if (!bodyValid(w, i) || !finite(p, n) || n < 7 || !validPose(p))
    return -1;
  auto *b = w->bodies[i].body.get();
  auto t = pose(p);
  if (!b->isKinematicObject())
    b->setInterpolationWorldTransform(t);
  b->setWorldTransform(t);
  if (b->getMotionState())
    b->getMotionState()->setWorldTransform(t);
  w->world->updateSingleAabb(b);
  b->activate(true);
  return 0;
} catch (...) {
  if (w)
    w->failed = true;
  return -3;
}
extern "C" int32_t ysm_physics_set_poses(ysm_physics_world *w,
                                         const int32_t *ids, const float *poses,
                                         int32_t count) try {
  if (w && w->failed)
    return -3;
  if (!w || count < 0 || count > 65536 || (count && (!ids || !poses)) ||
      !finite(poses, count * 7))
    return -1;
  for (int i = 0; i < count; i++)
    if (!bodyValid(w, ids[i]) || !validPose(poses + i * 7))
      return -1;
  for (int i = 0; i < count; i++) {
    auto *b = w->bodies[ids[i]].body.get();
    auto t = pose(poses + i * 7);
    if (!b->isKinematicObject())
      b->setInterpolationWorldTransform(t);
    b->setWorldTransform(t);
    if (b->getMotionState())
      b->getMotionState()->setWorldTransform(t);
    w->world->updateSingleAabb(b);
    b->activate(true);
  }
  return 0;
} catch (...) {
  if (w)
    w->failed = true;
  return -3;
}
extern "C" int32_t ysm_physics_set_kinematic(ysm_physics_world *w, int32_t i,
                                             int32_t e) try {
  if (w && w->failed)
    return -3;
  if (!bodyValid(w, i) || w->bodies[i].motion != 2)
    return -1;
  auto &s = w->bodies[i];
  auto *b = s.body.get();
  if (b->isKinematicObject() == (e != 0))
    return 0;
  w->world->removeRigidBody(b);
  b->setMassProps(e ? 0 : s.mass, e ? btVector3(0, 0, 0) : s.inertia);
  int f = b->getCollisionFlags() & ~(btCollisionObject::CF_STATIC_OBJECT |
                                     btCollisionObject::CF_KINEMATIC_OBJECT);
  b->setCollisionFlags(e ? f | btCollisionObject::CF_KINEMATIC_OBJECT : f);
  b->updateInertiaTensor();
  b->clearForces();
  b->setLinearVelocity({0, 0, 0});
  b->setAngularVelocity({0, 0, 0});
  b->setInterpolationLinearVelocity({0, 0, 0});
  b->setInterpolationAngularVelocity({0, 0, 0});
  b->setInterpolationWorldTransform(b->getWorldTransform());
  b->forceActivationState(e ? DISABLE_DEACTIVATION : ACTIVE_TAG);
  w->world->addRigidBody(b, s.group, s.mask);
  return 0;
} catch (...) {
  if (w)
    w->failed = true;
  return -3;
}
extern "C" int32_t ysm_physics_set_velocity(ysm_physics_world *w, int32_t i,
                                            const float *v, int32_t n) try {
  if (w && w->failed)
    return -3;
  if (!bodyValid(w, i) || !finite(v, n) || n < 6)
    return -1;
  auto *b = w->bodies[i].body.get();
  b->setLinearVelocity(vec(v));
  b->setAngularVelocity(vec(v + 3));
  b->activate(true);
  return 0;
} catch (...) {
  if (w)
    w->failed = true;
  return -3;
}
extern "C" int32_t ysm_physics_apply_impulse(ysm_physics_world *w, int32_t i,
                                             const float *v, int32_t n,
                                             int32_t local) try {
  if (w && w->failed)
    return -3;
  if (!bodyValid(w, i) || !finite(v, n) || n < 6 || local < 0 || local > 1)
    return -1;
  auto *b = w->bodies[i].body.get();
  btVector3 linear = vec(v), angular = vec(v + 3);
  if (local) {
    const auto &basis = b->getWorldTransform().getBasis();
    linear = basis * linear;
    angular = basis * angular;
  }
  b->applyCentralImpulse(linear);
  b->applyTorqueImpulse(angular);
  if (!validState(b))
    return numericFailure(w);
  b->activate(true);
  return 0;
} catch (...) {
  if (w)
    w->failed = true;
  return -3;
}
extern "C" int32_t ysm_physics_joint(ysm_physics_world *w, const float *c,
                                     int32_t n) try {
  if (w && w->failed)
    return -3;
  if (!w || w->joints.size() >= 65536 ||
      !validJointConfig(c, n, w->bodies.size()))
    return -1;
  int type = int(c[0]), a = int(c[1]), b = int(c[2]);
  auto &A = a == -1 ? w->fixed : *w->bodies[a].body;
  auto &B = b == -1 ? w->fixed : *w->bodies[b].body;
  auto fa = pose(c + 3), fb = pose(c + 10);
  std::unique_ptr<btTypedConstraint> j;
  switch (type) {
  case 0: {
    auto x =
        std::make_unique<btGeneric6DofSpringConstraint>(A, B, fa, fb, true);
    x->setLinearLowerLimit(vec(c + 17));
    x->setLinearUpperLimit(vec(c + 20));
    x->setAngularLowerLimit(vec(c + 23));
    x->setAngularUpperLimit(vec(c + 26));
    for (int axis = 0; axis < 6; axis++) {
      x->setStiffness(axis, c[29 + axis]);
      x->setDamping(axis, c[35]);
      x->enableSpring(axis, c[29 + axis] != 0);
    }
    x->setEquilibriumPoint();
    j = std::move(x);
    break;
  }
  case 1: {
    auto x = std::make_unique<btGeneric6DofConstraint>(A, B, fa, fb, true);
    x->setLinearLowerLimit(vec(c + 17));
    x->setLinearUpperLimit(vec(c + 20));
    x->setAngularLowerLimit(vec(c + 23));
    x->setAngularUpperLimit(vec(c + 26));
    j = std::move(x);
    break;
  }
  case 2:
    j = std::make_unique<btPoint2PointConstraint>(A, B, fa.getOrigin(),
                                                  fb.getOrigin());
    break;
  case 3: {
    auto x = std::make_unique<btConeTwistConstraint>(A, B, fa, fb);
    x->setLimit(c[26], c[27], c[28]);
    j = std::move(x);
    break;
  }
  case 4: {
    auto x = std::make_unique<btSliderConstraint>(A, B, fa, fb, true);
    x->setLowerLinLimit(c[17]);
    x->setUpperLinLimit(c[20]);
    x->setLowerAngLimit(c[23]);
    x->setUpperAngLimit(c[26]);
    j = std::move(x);
    break;
  }
  case 5: {
    auto x = std::make_unique<btHingeConstraint>(A, B, fa, fb, true);
    x->setLimit(c[23], c[26]);
    j = std::move(x);
    break;
  }
  default:
    return -1;
  }
  w->joints.push_back(std::move(j));
  w->world->addConstraint(w->joints.back().get(), c[36] != 0);
  return static_cast<int32_t>(w->joints.size() - 1);
} catch (...) {
  if (w)
    w->failed = true;
  return -3;
}
extern "C" int32_t ysm_physics_configure_joint(ysm_physics_world *w, int32_t i,
                                               const float *c, int32_t n) try {
  if (w && w->failed)
    return -3;
  if (!w || n < 1 || n > 15 || !finite(c, n) || !integer(c[0], 0, 5) || i < 0 ||
      static_cast<size_t>(i) >= w->joints.size() || n < 1)
    return -1;
  auto *j = w->joints[i].get();
  switch (static_cast<int>(c[0])) {
  case 0: {
    if (n < 13 || (j->getConstraintType() != D6_CONSTRAINT_TYPE &&
                   j->getConstraintType() != D6_SPRING_CONSTRAINT_TYPE))
      return -1;
    for (int axis = 0; axis < 6; axis++)
      if (!unit(c[axis + 1]) || c[axis + 7] < 0)
        return -1;
    auto *x = static_cast<btGeneric6DofConstraint *>(j);
    for (int axis = 0; axis < 6; axis++) {
      x->setParam(BT_CONSTRAINT_STOP_ERP, c[axis + 1], axis);
      x->setParam(BT_CONSTRAINT_STOP_CFM, c[axis + 7], axis);
    }
    return 0;
  }
  case 3:
    if (j->getConstraintType() != CONETWIST_CONSTRAINT_TYPE || n < 15)
      return -1;
    {
      auto *x = static_cast<btConeTwistConstraint *>(j);
      x->setLimit(c[1], c[2], c[3], c[4], c[5], c[6]);
      x->setDamping(c[7]);
      x->setFixThresh(c[8]);
      x->enableMotor(c[9] != 0);
      x->setMaxMotorImpulse(c[10]);
      x->setMotorTarget(btQuaternion(c[11], c[12], c[13], c[14]));
      return 0;
    }
  case 4:
    if (j->getConstraintType() != SLIDER_CONSTRAINT_TYPE || n < 7)
      return -1;
    {
      auto *x = static_cast<btSliderConstraint *>(j);
      x->setPoweredLinMotor(c[1] != 0);
      x->setTargetLinMotorVelocity(c[2]);
      x->setMaxLinMotorForce(c[3]);
      x->setPoweredAngMotor(c[4] != 0);
      x->setTargetAngMotorVelocity(c[5]);
      x->setMaxAngMotorForce(c[6]);
      return 0;
    }
  case 5:
    if (j->getConstraintType() != HINGE_CONSTRAINT_TYPE || n < 7)
      return -1;
    {
      auto *x = static_cast<btHingeConstraint *>(j);
      x->setLimit(x->getLowerLimit(), x->getUpperLimit(), c[1], c[2], c[3]);
      x->enableAngularMotor(c[4] != 0, c[5], c[6]);
      return 0;
    }
  default:
    return -1;
  }
} catch (...) {
  if (w)
    w->failed = true;
  return -3;
}
extern "C" int32_t ysm_physics_reset_forces(ysm_physics_world *w,
                                            int32_t i) try {
  if (w && w->failed)
    return -3;
  if (!bodyValid(w, i))
    return -1;
  auto *b = w->bodies[i].body.get();
  b->clearForces();
  b->setLinearVelocity({0, 0, 0});
  b->setAngularVelocity({0, 0, 0});
  b->setInterpolationLinearVelocity({0, 0, 0});
  b->setInterpolationAngularVelocity({0, 0, 0});
  b->activate(true);
  return 0;
} catch (...) {
  if (w)
    w->failed = true;
  return -3;
}
extern "C" int32_t ysm_physics_kinematic_filter(ysm_physics_world *w,
                                                int32_t enabled) try {
  if (w && w->failed)
    return -3;
  // Construction-only: never leave existing overlapping pairs with stale
  // filtering decisions.
  if (!w || enabled < 0 || enabled > 1 || !w->bodies.empty() ||
      !w->soft.empty())
    return -1;
  w->world->getPairCache()->setOverlapFilterCallback(
      enabled ? &w->kinematicFilter : nullptr);
  return 0;
} catch (...) {
  if (w)
    w->failed = true;
  return -3;
}
extern "C" int32_t ysm_physics_set_gravity(ysm_physics_world *w, const float *g,
                                           int32_t n) try {
  if (w && w->failed)
    return -3;
  if (!w || !finite(g, n) || n < 3)
    return -1;
  w->world->setGravity(vec(g));
  w->world->getWorldInfo().m_gravity = vec(g);
  for (auto &b : w->bodies)
    b.body->activate(true);
  for (auto &b : w->soft)
    b->activate(true);
  return 0;
} catch (...) {
  if (w)
    w->failed = true;
  return -3;
}
extern "C" int32_t ysm_physics_step(ysm_physics_world *w) try {
  if (w && w->failed)
    return -3;
  if (!w)
    return -1;
  for (const auto &b : w->bodies)
    if (!validState(b.body.get()) || !finite(b.body->getTotalForce()) ||
        !finite(b.body->getTotalTorque()))
      return numericFailure(w);
  for (const auto &p : w->pins) {
    auto &node = w->soft[p.soft]->m_nodes[p.vertex];
    node.m_v = (p.position - node.m_x) / w->step;
  }
  w->world->stepSimulation(w->step, 0, w->step);
  for (const auto &p : w->pins) {
    w->soft[p.soft]->m_nodes[p.vertex].m_v = {0, 0, 0};
    w->pinSlots[p.soft][p.vertex] = -1;
  }
  w->pins.clear();
  for (const auto &b : w->bodies) {
    if (!validState(b.body.get()))
      return numericFailure(w);
  }
  for (const auto &s : w->soft)
    for (int i = 0; i < s->m_nodes.size(); i++) {
      const auto &n = s->m_nodes[i];
      if (!finite(n.m_x) || !finite(n.m_v) || !finite(n.m_n))
        return numericFailure(w);
    }
  w->world->getWorldInfo().m_sparsesdf.GarbageCollect();
  return 0;
} catch (...) {
  if (w)
    w->failed = true;
  return -3;
}
extern "C" int32_t ysm_physics_body_count(const ysm_physics_world *w) try {
  if (w && w->failed)
    return -3;
  return w ? static_cast<int32_t>(w->bodies.size()) : -1;
} catch (...) {
  if (w)
    w->failed = true;
  return -3;
}
extern "C" int32_t ysm_physics_read_bodies(const ysm_physics_world *w, float *o,
                                           int32_t n) try {
  if (w && w->failed)
    return -3;
  if (!w || !o || n < static_cast<int32_t>(w->bodies.size()) * 13)
    return -1;
  for (const auto &b : w->bodies)
    if (!validState(b.body.get()))
      return numericFailure(w);
  for (const auto &x : w->bodies) {
    auto t = x.body->getWorldTransform();
    putVec(o, t.getOrigin());
    auto q = t.getRotation();
    o[3] = q.x();
    o[4] = q.y();
    o[5] = q.z();
    o[6] = q.w();
    putVec(o + 7, x.body->getLinearVelocity());
    putVec(o + 10, x.body->getAngularVelocity());
    o += 13;
  }
  return 0;
} catch (...) {
  if (w)
    w->failed = true;
  return -3;
}
extern "C" int32_t ysm_physics_soft(ysm_physics_world *w, const float *v,
                                    int32_t vc, const int32_t *ix, int32_t ic,
                                    const int32_t *pins, int32_t pc,
                                    int32_t rope, float mass, const float *c,
                                    int32_t cn) try {
  if (w && w->failed)
    return -3;
  if (!w || !v || vc < 2 || vc > 1000000 || w->softVertexCount + vc > 1000000 ||
      w->soft.size() >= 1024 || !ix || ic < 2 || ic > 12000000 || pc < 0 ||
      pc > vc || !std::isfinite(mass) || mass <= 0 || !finite(v, vc * 3) ||
      (c && !finite(c, cn)))
    return -1;
  if (ic % (rope ? 2 : 3) != 0)
    return -1;
  for (int i = 0; i < ic; i++)
    if (ix[i] < 0 || ix[i] >= vc)
      return -1;
  for (int i = 0; i < pc; i++)
    if (!pins || pins[i] < 0 || pins[i] >= vc)
      return -1;
  if ((c && cn != 34) || (!c && cn != 0))
    return -1;
  if (c) {
    for (int i = 18; i < 22; i++)
      if (!integer(c[i], 0, 10000))
        return -1;
    if (!integer(c[25], 0, 6) || !integer(c[26], 0, 65535) ||
        !integer(c[27], 0, 10000) || !integer(c[28], 0, 10000) ||
        !integer(c[31], 0, 65535) || !integer(c[32], 0, 65535) || c[33] < 0)
      return -1;
  }
  for (int i = 0; i < ic; i += (rope ? 2 : 3)) {
    auto edge = vec(v + ix[i + 1] * 3) - vec(v + ix[i] * 3);
    double length = edge.length2();
    if (!std::isfinite(length) || length < 1e-30)
      return -1;
    if (!rope) {
      auto other = vec(v + ix[i + 2] * 3) - vec(v + ix[i] * 3);
      double area = edge.cross(other).length2();
      if (!std::isfinite(area) || area <= 1e-12 * length * other.length2())
        return -1;
    }
  }
  std::vector<btVector3> positions;
  positions.reserve(vc);
  for (int i = 0; i < vc; i++)
    positions.push_back(vec(v + i * 3));
  auto owner = std::make_unique<btSoftBody>(&w->world->getWorldInfo(), vc,
                                            positions.data(), nullptr);
  auto *s = owner.get();
  auto *material = s->m_materials[0];
  if (c && cn >= 33) {
    material->m_kLST = c[22];
    material->m_kAST = c[23];
    material->m_kVST = c[24];
  }
  std::set<std::pair<int, int>> edges;
  auto link = [&](int a, int b) {
    if (a > b)
      std::swap(a, b);
    if (a != b && edges.emplace(a, b).second)
      s->appendLink(a, b, material);
  };
  for (int i = 0; i < ic; i += (rope ? 2 : 3)) {
    int a = ix[i], b = ix[i + 1];
    link(a, b);
    if (!rope) {
      int d = ix[i + 2];
      link(b, d);
      link(d, a);
      s->appendFace(a, b, d, material);
    }
  }
  int group = 1, mask = 0xffff;
  if (c && cn >= 33) {
    auto &x = s->m_cfg;
    x.kVCF = c[0];
    x.kDP = c[1];
    x.kDG = c[2];
    x.kLF = c[3];
    x.kPR = c[4];
    x.kVC = c[5];
    x.kDF = c[6];
    x.kMT = c[7];
    x.kCHR = c[8];
    x.kKHR = c[9];
    x.kSHR = c[10];
    x.kAHR = c[11];
    x.kSRHR_CL = c[12];
    x.kSKHR_CL = c[13];
    x.kSSHR_CL = c[14];
    x.kSR_SPLT_CL = c[15];
    x.kSK_SPLT_CL = c[16];
    x.kSS_SPLT_CL = c[17];
    x.viterations = int(c[18]);
    x.piterations = int(c[19]);
    x.diterations = int(c[20]);
    x.citerations = int(c[21]);
    x.aeromodel = static_cast<btSoftBody::eAeroModel::_>(int(c[25]));
    x.collisions = int(c[26]);
    if (c[28] > 0)
      s->generateBendingConstraints(int(c[28]), material);
    group = int(c[31]);
    mask = int(c[32]);
  }
  s->setTotalMass(mass, false);
  for (int i = 0; i < pc; i++)
    s->setMass(pins[i], 0);
  s->getCollisionShape()->setMargin(c && cn >= 34 ? c[33] : 0.02f);
  s->setPose(s->m_cfg.kVC > 0, s->m_cfg.kMT > 0);
  if (c && cn >= 33) {
    if (c[29] != 0)
      s->generateClusters(int(c[27]));
    if (c[30] != 0)
      s->randomizeConstraints();
  }
  w->pinSlots.emplace_back(vc, -1);
  w->soft.push_back(std::move(owner));
  w->softVertexCount += vc;
  w->world->addSoftBody(s, group, mask);
  return int32_t(w->soft.size() - 1);
} catch (...) {
  if (w)
    w->failed = true;
  return -3;
}
extern "C" int32_t ysm_physics_soft_count(const ysm_physics_world *w,
                                          int32_t i) try {
  if (w && w->failed)
    return -3;
  return softValid(w, i) ? static_cast<int32_t>(w->soft[i]->m_nodes.size())
                         : -1;
} catch (...) {
  if (w)
    w->failed = true;
  return -3;
}
extern "C" int32_t ysm_physics_read_soft(const ysm_physics_world *w, int32_t i,
                                         float *o, int32_t n,
                                         int32_t normals) try {
  if (w && w->failed)
    return -3;
  if (!softValid(w, i) || !o ||
      n < static_cast<int32_t>(w->soft[i]->m_nodes.size()) * 3)
    return -1;
  for (int k = 0; k < w->soft[i]->m_nodes.size(); k++)
    if (!finite(normals ? w->soft[i]->m_nodes[k].m_n
                        : w->soft[i]->m_nodes[k].m_x))
      return numericFailure(w);
  for (int k = 0; k < w->soft[i]->m_nodes.size(); k++)
    putVec(o + k * 3,
           normals ? w->soft[i]->m_nodes[k].m_n : w->soft[i]->m_nodes[k].m_x);
  return 0;
} catch (...) {
  if (w)
    w->failed = true;
  return -3;
}
extern "C" int32_t ysm_physics_soft_pin(ysm_physics_world *w, int32_t i,
                                        int32_t v, const float *p,
                                        int32_t n) try {
  if (w && w->failed)
    return -3;
  if (!softValid(w, i) || !finite(p, n) || n < 3 || v < 0 ||
      v >= w->soft[i]->m_nodes.size())
    return -1;
  if (w->soft[i]->m_nodes[v].m_im != 0)
    return -1;
  int32_t slot = w->pinSlots[i][v];
  if (slot >= 0)
    w->pins[slot].position = vec(p);
  else {
    w->pins.push_back({i, v, vec(p)});
    w->pinSlots[i][v] = int32_t(w->pins.size() - 1);
  }
  w->soft[i]->activate(true);
  return 0;
} catch (...) {
  if (w)
    w->failed = true;
  return -3;
}
extern "C" int32_t ysm_physics_soft_anchor(ysm_physics_world *w, int32_t i,
                                           int32_t v, int32_t b,
                                           int32_t d) try {
  if (w && w->failed)
    return -3;
  if (!softValid(w, i) || !bodyValid(w, b) || v < 0 ||
      v >= w->soft[i]->m_nodes.size())
    return -1;
  w->soft[i]->appendAnchor(v, w->bodies[b].body.get(), d != 0);
  return 0;
} catch (...) {
  if (w)
    w->failed = true;
  return -3;
}

extern "C" int32_t ysm_physics_build(ysm_physics_world *w, const float *bodies,
                                     int32_t bc, const float *joints,
                                     int32_t jc) try {
  if (!w || bc < 0 || jc < 0 || bc > 65536 || jc > 65536 ||
      w->bodies.size() + bc > 65536 || w->joints.size() + jc > 65536 ||
      (bc && !bodies) || (jc && !joints))
    return -1;
  if (w->failed)
    return -3;
  for (int i = 0; i < bc; i++)
    if (!validBodyConfig(bodies + i * 20, 20))
      return -1;
  for (int i = 0; i < jc; i++)
    if (!validJointConfig(joints + i * 37, 37, w->bodies.size() + bc))
      return -1;
  w->bodies.reserve(w->bodies.size() + bc);
  w->joints.reserve(w->joints.size() + jc);
  for (int i = 0; i < bc; i++) {
    int r = ysm_physics_body(w, bodies + i * 20, 20);
    if (r < 0) {
      w->failed = true;
      return r;
    }
  }
  for (int i = 0; i < jc; i++) {
    int r = ysm_physics_joint(w, joints + i * 37, 37);
    if (r < 0) {
      w->failed = true;
      return r;
    }
  }
  return 0;
} catch (...) {
  if (w)
    w->failed = true;
  return -3;
}
extern "C" int32_t ysm_physics_impulses(ysm_physics_world *w,
                                        const int32_t *ids, const float *values,
                                        int32_t count) try {
  if (!w || count < 0 || count > 65536 || (count && (!ids || !values)) ||
      !finite(values, count * 7))
    return -1;
  if (w->failed)
    return -3;
  for (int i = 0; i < count; i++)
    if (!bodyValid(w, ids[i]) || !integer(values[i * 7 + 6], 0, 1))
      return -1;
  for (int i = 0; i < count; i++) {
    int r = ysm_physics_apply_impulse(w, ids[i], values + i * 7, 6,
                                      int(values[i * 7 + 6]));
    if (r < 0)
      return r;
  }
  return 0;
} catch (...) {
  if (w)
    w->failed = true;
  return -3;
}

extern "C" int32_t ysm_physics_soft_pins(ysm_physics_world *w, int32_t soft,
                                         const int32_t *ids,
                                         const float *positions,
                                         int32_t count) try {
  if (!softValid(w, soft) || count < 0 ||
      count > w->soft[soft]->m_nodes.size() ||
      (count && (!ids || !positions)) || !finite(positions, count * 3))
    return -1;
  if (w->failed)
    return -3;
  for (int i = 0; i < count; i++)
    if (ids[i] < 0 || ids[i] >= w->soft[soft]->m_nodes.size() ||
        w->soft[soft]->m_nodes[ids[i]].m_im != 0)
      return -1;
  w->pins.reserve(w->pins.size() + count);
  for (int i = 0; i < count; i++) {
    int r = ysm_physics_soft_pin(w, soft, ids[i], positions + i * 3, 3);
    if (r < 0)
      return r;
  }
  return 0;
} catch (...) {
  if (w)
    w->failed = true;
  return -3;
}

extern "C" int32_t ysm_physics_forces(ysm_physics_world *w, const int32_t *ids,
                                      const float *values, int32_t count) try {
  if (!w || count < 0 || count > 65536 || (count && (!ids || !values)) ||
      !finite(values, count * 7))
    return -1;
  if (w->failed)
    return -3;
  for (int i = 0; i < count; i++)
    if (!bodyValid(w, ids[i]) || !integer(values[i * 7 + 6], 0, 1))
      return -1;
  for (int i = 0; i < count; i++) {
    auto *b = w->bodies[ids[i]].body.get();
    btVector3 linear = vec(values + i * 7), angular = vec(values + i * 7 + 3);
    if (values[i * 7 + 6] != 0) {
      linear = b->getWorldTransform().getBasis() * linear;
      angular = b->getWorldTransform().getBasis() * angular;
    }
    b->applyCentralForce(linear);
    b->applyTorque(angular);
    if (!finite(b->getTotalForce()) || !finite(b->getTotalTorque()))
      return numericFailure(w);
    b->activate(true);
  }
  return 0;
} catch (...) {
  if (w)
    w->failed = true;
  return -3;
}
static btVector3 limited(const btVector3 &v, float limit) {
  double length = std::hypot(double(v.x()), double(v.y()), double(v.z()));
  if (length <= limit)
    return v;
  double scale = limit / length;
  return {btScalar(v.x() * scale), btScalar(v.y() * scale),
          btScalar(v.z() * scale)};
}
extern "C" int32_t ysm_physics_clamp_velocities(ysm_physics_world *w,
                                                const int32_t *ids,
                                                int32_t count, float linear,
                                                float angular) try {
  if (!w || count < 0 || count > 65536 || (count && !ids) ||
      !std::isfinite(linear) || !std::isfinite(angular) || linear <= 0 ||
      angular <= 0)
    return -1;
  if (w->failed)
    return -3;
  for (int i = 0; i < count; i++) {
    if (!bodyValid(w, ids[i]))
      return -1;
    if (!validState(w->bodies[ids[i]].body.get())) {
      w->failed = true;
      return -2;
    }
  }
  for (int i = 0; i < count; i++) {
    auto *b = w->bodies[ids[i]].body.get();
    if (b->isStaticOrKinematicObject())
      continue;
    b->setLinearVelocity(limited(b->getLinearVelocity(), linear));
    b->setAngularVelocity(limited(b->getAngularVelocity(), angular));
  }
  return 0;
} catch (...) {
  if (w)
    w->failed = true;
  return -3;
}
