// Shared visual host physics policy for native Bullet and the JVM/WASM reactor.
// Copyright Oh My Yes Steve Model contributors. SPDX-License-Identifier:
// Apache-2.0
#pragma once
#include <BulletSoftBody/btSoftBody.h>
#include <BulletSoftBody/btSoftRigidDynamicsWorld.h>
#include <algorithm>
#include <btBulletDynamicsCommon.h>
#include <cmath>
#include <cstdint>
#include <memory>
#include <unordered_map>
#include <vector>
struct YsmHostPairFilter : btOverlapFilterCallback {
  bool excludeMixed = false;
  bool needBroadphaseCollision(btBroadphaseProxy *a,
                               btBroadphaseProxy *b) const override {
    auto *x = static_cast<btCollisionObject *>(a->m_clientObject);
    auto *y = static_cast<btCollisionObject *>(b->m_clientObject);
    // Source masks describe self-collision, not permission to pass through host
    // terrain.
    if (x && y && (x->getUserIndex() == -987 || y->getUserIndex() == -987))
      return !(x->isStaticOrKinematicObject() &&
               y->isStaticOrKinematicObject());
    if (!(a->m_collisionFilterGroup & b->m_collisionFilterMask) ||
        !(b->m_collisionFilterGroup & a->m_collisionFilterMask))
      return false;
    return !excludeMixed || !x || !y ||
           x->isKinematicObject() == y->isKinematicObject();
  }
};
struct YsmHostEnvironment {
  struct Cell {
    int x, y, z;
    bool operator==(const Cell &b) const {
      return x == b.x && y == b.y && z == b.z;
    }
  };
  struct CellHash {
    size_t operator()(const Cell &p) const {
      return uint32_t(p.x) * 73856093u ^ uint32_t(p.y) * 19349663u ^
             uint32_t(p.z) * 83492791u;
    }
  };
  std::vector<btCollisionObject *> terrain;
  std::vector<btConvexHullShape *> terrainShapes;
  std::vector<float> environment;
  int fluidCount = 0;
  std::unordered_map<Cell, std::vector<int>, CellHash> fluidCells;
  std::vector<int> largeFluids;
  bool reset = false;
  static btVector3 vec(const float *p) { return {p[0], p[1], p[2]}; }
  static bool valid(const float *p, int count, int boxes, int fluids) {
    if (!p || boxes < 0 || boxes > 4096 || fluids < 0 || fluids > 4096 ||
        count != 51 + boxes * 24 + fluids * 10)
      return false;
    for (int i = 0; i < count; i++)
      if (!std::isfinite(p[i]))
        return false;
    for (int offset : {0, 16}) {
      btMatrix3x3 basis(p[offset], p[offset + 4], p[offset + 8], p[offset + 1],
                        p[offset + 5], p[offset + 9], p[offset + 2],
                        p[offset + 6], p[offset + 10]);
      auto determinant = basis.determinant();
      if (!std::isfinite(determinant) || determinant == 0)
        return false;
    }
    for (int offset : {0, 16})
      if (p[offset + 3] != 0 || p[offset + 7] != 0 || p[offset + 11] != 0 ||
          p[offset + 15] != 1)
        return false;
    for (int i = 47; i < 50; i++)
      if (p[i] < 0 || p[i] > 100)
        return false;
    if (p[50] != 0 && p[50] != 1)
      return false;
    for (int i = 0; i < fluids; i++) {
      const float *f = p + 51 + boxes * 24 + i * 10;
      if (f[0] >= f[3] || f[1] >= f[4] || f[2] >= f[5] || f[9] < 0 || f[9] > 10)
        return false;
    }
    return true;
  }
  // Column-major affine placement and its inverse; all world positions are
  // rebased by the Java host.
  static btVector3 direction(const float *m, const btVector3 &p) {
    return {m[0] * p.x() + m[4] * p.y() + m[8] * p.z(),
            m[1] * p.x() + m[5] * p.y() + m[9] * p.z(),
            m[2] * p.x() + m[6] * p.y() + m[10] * p.z()};
  }
  static btVector3 point(const float *m, const btVector3 &p) {
    return direction(m, p) + vec(m + 12);
  }
  void update(btSoftRigidDynamicsWorld &world, int boxes, int fluids,
              const float *p) {
    terrain.reserve(boxes);
    terrainShapes.reserve(boxes);
    while (static_cast<int>(terrain.size()) > boxes) {
      world.removeCollisionObject(terrain.back());
      delete terrain.back();
      delete terrainShapes.back();
      terrain.pop_back();
      terrainShapes.pop_back();
    }
    const float *fluidStart = p + 51 + boxes * 24;
    bool fluidChanged =
        environment.size() != size_t(51 + fluids * 10) ||
        !std::equal(environment.begin() + 51, environment.end(), fluidStart);
    environment.resize(51 + fluids * 10);
    std::copy(p, p + 51, environment.begin());
    std::copy(fluidStart, fluidStart + fluids * 10, environment.begin() + 51);
    fluidCount = fluids;
    if (fluidChanged) {
      fluidCells.clear();
      largeFluids.clear();
    }
    for (int i = 0; fluidChanged && i < fluids; i++) {
      const float *f = p + 51 + boxes * 24 + i * 10;
      if (f[3] - f[0] > 2 || f[4] - f[1] > 2 || f[5] - f[2] > 2 ||
          std::abs(f[0]) > 1000000 || std::abs(f[1]) > 1000000 ||
          std::abs(f[2]) > 1000000) {
        largeFluids.push_back(i);
        continue;
      }
      for (int x = int(std::floor(f[0])); x <= int(std::floor(f[3])); x++)
        for (int y = int(std::floor(f[1])); y <= int(std::floor(f[4])); y++)
          for (int z = int(std::floor(f[2])); z <= int(std::floor(f[5])); z++)
            fluidCells[{x, y, z}].push_back(i);
    }
    for (int i = 0; i < boxes; i++) {
      if (i == static_cast<int>(terrain.size())) {
        auto shapeOwner = std::make_unique<btConvexHullShape>();
        auto *shape = shapeOwner.get();
        for (int j = 0; j < 8; j++)
          shape->addPoint(vec(p + 51 + i * 24 + j * 3), false);
        shape->recalcLocalAabb();
        shape->setMargin(.001f);
        auto objectOwner = std::make_unique<btCollisionObject>();
        auto *object = objectOwner.get();
        object->setCollisionShape(shape);
        object->setWorldTransform(btTransform::getIdentity());
        object->setCollisionFlags(btCollisionObject::CF_STATIC_OBJECT);
        object->setUserIndex(-987);
        object->setFriction(.5f);
        world.addCollisionObject(object, 1, 0xffff);
        terrain.push_back(objectOwner.release());
        terrainShapes.push_back(shapeOwner.release());
      } else {
        auto *shape = terrainShapes[i];
        bool changed = false;
        for (int j = 0; j < 8; j++) {
          auto next = vec(p + 51 + i * 24 + j * 3);
          if (shape->getUnscaledPoints()[j] != next) {
            shape->getUnscaledPoints()[j] = next;
            changed = true;
          }
        }
        if (changed) {
          shape->recalcLocalAabb();
          world.getPairCache()->cleanProxyFromPairs(
              terrain[i]->getBroadphaseHandle(), world.getDispatcher());
          world.updateSingleAabb(terrain[i]);
        }
      }
    }
    auto gravity = direction(p + 16, vec(p + 44));
    world.setGravity(gravity);
    world.getWorldInfo().m_gravity = gravity;
    reset = p[50] == 1;
  }
  void clear(btSoftRigidDynamicsWorld &world) {
    for (auto *object : terrain) {
      world.removeCollisionObject(object);
      delete object;
    }
    for (auto *shape : terrainShapes)
      delete shape;
    terrain.clear();
    terrainShapes.clear();
    environment.clear();
    fluidCount = 0;
    fluidCells.clear();
    largeFluids.clear();
  }
  btVector3 acceleration(const btVector3 &position, const btVector3 &velocity,
                         float dt) const {
    const float *p = environment.data();
    auto r = direction(p, position), v = direction(p, velocity),
         omega = vec(p + 38);
    auto acceleration = -(vec(p + 35) + vec(p + 41).cross(r) +
                          omega.cross(omega.cross(r)) + 2 * omega.cross(v)) *
                        p[47];
    auto location = point(p, position);
    auto fluidAcceleration = [&](int i, btVector3 &result) {
      const float *f = p + 51 + i * 10;
      if (location.x() < f[0] || location.y() < f[1] || location.z() < f[2] ||
          location.x() > f[3] || location.y() > f[4] || location.z() > f[5])
        return false;
      // Stable implicit drag, independent of the chosen simulation frequency.
      result += (vec(f + 6) - v - vec(p + 32) - omega.cross(r)) *
                    (p[48] / (1 + p[48] * dt)) -
                vec(p + 44) * (p[49] * f[9]);
      return true;
    };
    if (std::abs(location.x()) < 1000000 && std::abs(location.y()) < 1000000 &&
        std::abs(location.z()) < 1000000) {
      auto found = fluidCells.find({int(std::floor(location.x())),
                                    int(std::floor(location.y())),
                                    int(std::floor(location.z()))});
      if (found != fluidCells.end())
        for (int i : found->second)
          if (fluidAcceleration(i, acceleration))
            return direction(p + 16, acceleration);
    }
    for (int i : largeFluids)
      if (fluidAcceleration(i, acceleration))
        break;
    return direction(p + 16, acceleration);
  }
  void apply(btRigidBody *body, float dt) const {
    if (environment.empty() || body->isStaticOrKinematicObject() ||
        body->getInvMass() <= 0)
      return;
    body->activate(true);
    body->applyCentralForce(acceleration(body->getWorldTransform().getOrigin(),
                                         body->getLinearVelocity(), dt) /
                            body->getInvMass());
    const float *p = environment.data();
    auto axial = [p](const btVector3 &vector) {
      auto result = direction(p + 16, vector);
      auto length = vector.length();
      btMatrix3x3 basis(p[0], p[4], p[8], p[1], p[5], p[9], p[2], p[6], p[10]);
      return result.length2() > 1e-20f ? result.normalized() * length *
                                             (basis.determinant() < 0 ? -1 : 1)
                                       : btVector3(0, 0, 0);
    };
    auto alpha = -axial(vec(p + 41)) -
                 axial(vec(p + 38)).cross(body->getAngularVelocity());
    body->applyTorque(body->getInvInertiaTensorWorld().inverse() * alpha *
                      p[47]);
  }
  void apply(btSoftBody *body, float dt) const {
    if (environment.empty())
      return;
    for (int i = 0; i < body->m_nodes.size(); i++) {
      auto &node = body->m_nodes[i];
      if (reset) {
        node.m_v.setZero();
        node.m_f.setZero();
        node.m_q = node.m_x;
      }
      if (node.m_im > 0)
        node.m_f += acceleration(node.m_x, node.m_v, dt) / node.m_im;
    }
  }
  void finishStep() { reset = false; }
};
