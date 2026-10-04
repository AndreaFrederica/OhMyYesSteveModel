import fs from 'node:fs';
import path from 'node:path';
import * as THREE from 'three';
import { GLTFLoader } from 'three/examples/jsm/loaders/GLTFLoader.js';
import { VRMAnimationLoaderPlugin, createVRMAnimationClip } from '@pixiv/three-vrm-animation';
import { VRMRotationConstraint, VRMRollConstraint, VRMAimConstraint, VRMNodeConstraintManager,
  VRMExpressionManager, VRMExpression, VRMExpressionMorphTargetBind, VRMExpressionMaterialColorBind,
  VRMExpressionTextureTransformBind, VRMSpringBoneJoint, VRMSpringBoneManager,
  VRMSpringBoneCollider, VRMSpringBoneColliderShapeSphere, VRMSpringBoneColliderShapeCapsule, VRMLoaderPlugin } from '@pixiv/three-vrm';

const output = process.argv[2];
const nodes = Array.from({length:6}, () => new THREE.Object3D());
const parents = [-1,0,0,0,0,2];
parents.forEach((p,i) => { if(p>=0) nodes[p].add(nodes[i]); });
nodes.forEach((n,i) => { n.position.set(i*.23,i*.17,-i*.11);n.quaternion.setFromEuler(new THREE.Euler(i*.19,-i*.07,i*.13,'YZX')); });
nodes[0].scale.setScalar(1.7);
const rest = nodes.map(n=>({translation:n.position.toArray(),rotation:n.quaternion.toArray(),scale:n.scale.toArray()}));
const manager = new VRMNodeConstraintManager();
const declarations=[{node:2,source:1,type:'ROTATION',weight:.7},{node:3,source:1,type:'ROLL',axis:'Y',weight:.65},
  {node:4,source:5,type:'AIM',axis:'NegativeZ',weight:.8},{node:5,source:2,type:'ROTATION',weight:.43}];
const constraints = declarations.map(d=>{
  const C = d.type==='ROTATION'?VRMRotationConstraint:d.type==='ROLL'?VRMRollConstraint:VRMAimConstraint;
  const c = new C(nodes[d.node],nodes[d.source]);c.weight=d.weight;
  if(d.type==='ROLL') c.rollAxis=d.axis;
  if(d.type==='AIM') c.aimAxis=d.axis;
  return c;
});
constraints.toReversed().forEach(c=>manager.addConstraint(c));
manager.setInitState();
const frames=[];
for(let frame=0;frame<=120;frame++) {
  nodes.forEach((n,i)=>{ n.position.fromArray(rest[i].translation);n.quaternion.fromArray(rest[i].rotation); });
  nodes[0].quaternion.multiply(new THREE.Quaternion().setFromEuler(new THREE.Euler(frame*.007,frame*.011,0)));
  nodes[1].quaternion.multiply(new THREE.Quaternion().setFromEuler(new THREE.Euler(Math.sin(frame*.04),frame*.039,frame*.013,'ZYX')));
  nodes[1].position.y+=Math.sin(frame*.031)*.2;
  const input=nodes.map(n=>({translation:n.position.toArray(),rotation:n.quaternion.toArray(),scale:n.scale.toArray()}));
  manager.update();nodes[0].updateMatrixWorld(true);
  frames.push({input,rotations:nodes.map(n=>n.quaternion.toArray()),global:nodes.map(n=>n.matrixWorld.toArray())});
}
fs.writeFileSync(path.join(output,'constraints.json'),JSON.stringify({parents,rest,declarations,frames}));

const em = new VRMExpressionManager();
const material = new THREE.MeshStandardMaterial();material.color.fromArray([.2,.3,.4]);material.opacity=.8;
material.map = new THREE.Texture();material.map.repeat.set(2,3);material.map.offset.set(.1,.2);
const mesh=new THREE.Mesh();mesh.morphTargetInfluences=[0,0,0];
const happy=new VRMExpression('happy');happy.overrideBlink='blend';
happy.addBind(new VRMExpressionMorphTargetBind({primitives:[mesh],index:0,weight:.8}));
happy.addBind(new VRMExpressionMaterialColorBind({material,type:'color',targetValue:new THREE.Color().fromArray([.9,.1,.7]),targetAlpha:.2}));
happy.addBind(new VRMExpressionTextureTransformBind({material,scale:new THREE.Vector2(.5,1),offset:new THREE.Vector2(.4,.6)}));
const sad=new VRMExpression('sad');sad.isBinary=true;sad.overrideMouth='block';
sad.addBind(new VRMExpressionMorphTargetBind({primitives:[mesh],index:0,weight:.3}));
const blink=new VRMExpression('blink');blink.isBinary=true;blink.addBind(new VRMExpressionMorphTargetBind({primitives:[mesh],index:1,weight:1}));
const aa=new VRMExpression('aa');aa.addBind(new VRMExpressionMorphTargetBind({primitives:[mesh],index:2,weight:1}));
[happy,sad,blink,aa].forEach(e=>em.registerExpression(e));
const expressionFrames=[];
for(const h of [0,.01,.4,.5,.51,.9,1]) for(const s of [0,.49,.5,.51,1]) {
  const weights={happy:h,sad:s,blink:.8,aa:.75};Object.entries(weights).forEach(([k,v])=>em.setValue(k,v));em.update();
  expressionFrames.push({weights,morphs:mesh.morphTargetInfluences.slice(),color:[...material.color.toArray(),material.opacity],scale:material.map.repeat.toArray(),offset:material.map.offset.toArray()});
}
fs.writeFileSync(path.join(output,'expressions.json'),JSON.stringify(expressionFrames));
console.log('Reference: 121 constraint frames and 35 expression combinations');

const springCases=[];
for(const useCenter of [false,true]) for(const capsule of [false,true]) {
  const ns=Array.from({length:4},()=>new THREE.Object3D());ns[0].add(ns[1],ns[3]);ns[1].add(ns[2]);
  ns[1].position.set(.1,.7,-.1);ns[2].position.set(.05,-.55,.03);
  ns[1].quaternion.setFromEuler(new THREE.Euler(.17,-.09,.13));
  const state=ns.map(n=>({translation:n.position.toArray(),rotation:n.quaternion.toArray(),scale:n.scale.toArray()}));
  const params={hitRadius:.04,stiffness:.8,gravityPower:.3,gravityDir:[.2,-.97,.1],dragForce:.23};
  const colliderData={node:3,offset:[.1,.19,-.08],tail:capsule?[.3,.1,-.08]:null,radius:.15};
  const collider=new VRMSpringBoneCollider(capsule?
    new VRMSpringBoneColliderShapeCapsule({offset:new THREE.Vector3().fromArray(colliderData.offset),tail:new THREE.Vector3().fromArray(colliderData.tail),radius:colliderData.radius}):
    new VRMSpringBoneColliderShapeSphere({offset:new THREE.Vector3().fromArray(colliderData.offset),radius:colliderData.radius}));
  ns[3].add(collider);
  const bone=new VRMSpringBoneJoint(ns[1],ns[2],{...params,gravityDir:new THREE.Vector3().fromArray(params.gravityDir)},[{colliders:[collider]}]);
  if(useCenter) bone.center=ns[0];
  const sm=new VRMSpringBoneManager();sm.addJoint(bone);ns[0].updateMatrixWorld(true);sm.setInitState();
  const frames=[];
  for(let frame=1;frame<=240;frame++) {
    ns[0].position.set(Math.sin(frame*.021)*.23,Math.sin(frame*.017)*.12,Math.sin(frame*.03)*.15);
    ns[0].quaternion.setFromEuler(new THREE.Euler(Math.sin(frame*.019)*.1,frame*.013,Math.sin(frame*.027)*.3));
    ns[0].updateMatrixWorld(true);sm.update(1/60);ns[0].updateMatrixWorld(true);
    const input=state.map((v,i)=>i===0?{translation:ns[0].position.toArray(),rotation:ns[0].quaternion.toArray(),scale:[1,1,1]}:v);
    frames.push({input,rotations:ns.map(n=>n.quaternion.toArray()),global:ns.map(n=>n.matrixWorld.toArray())});
  }
  springCases.push({parents:[-1,0,1,0],rest:state,center:useCenter?0:-1,params,collider:colliderData,frames});
}
fs.writeFileSync(path.join(output,'springs.json'),JSON.stringify(springCases));
console.log('Reference: 4 SpringBone cases, 240 frames each, center/world and sphere/capsule');

const avatarBones=[['hips',-1,[0,1,0]],['spine',0,[0,.2,0]],['head',1,[0,.5,0]],
  ['leftUpperLeg',0,[.1,-.05,0]],['leftLowerLeg',3,[0,-.4,0]],['leftFoot',4,[0,-.4,.1]],
  ['rightUpperLeg',0,[-.1,-.05,0]],['rightLowerLeg',6,[0,-.4,0]],['rightFoot',7,[0,-.4,.1]],
  ['leftUpperArm',1,[.3,.3,0]],['leftLowerArm',9,[.3,0,0]],['leftHand',10,[.3,0,0]],
  ['rightUpperArm',1,[-.3,.3,0]],['rightLowerArm',12,[-.3,0,0]],['rightHand',13,[-.3,0,0]],
  ['hair',2,[0,.1,0]],['hairTip',15,[0,-.3,0]]];
const avatarNodes=avatarBones.map(([name,p,translation],i)=>({name,translation,children:avatarBones.flatMap((b,c)=>b[1]===i?[c]:[])}));
const avatar={asset:{version:'2.0',generator:'YSM synthetic portable runtime fixture'},nodes:avatarNodes,scenes:[{nodes:[0]}],scene:0,
  extensionsUsed:['VRMC_vrm','VRMC_springBone'],extensions:{
    VRMC_vrm:{specVersion:'1.0',meta:{name:'Portable avatar',authors:['YSM'],licenseUrl:'https://vrm.dev/licenses/1.0/'},
      humanoid:{humanBones:Object.fromEntries(avatarBones.slice(0,15).map((b,node)=>[b[0],{node}]))},expressions:{preset:{happy:{}}}},
    VRMC_springBone:{specVersion:'1.0',springs:[{joints:[{node:15,gravityPower:.5,gravityDir:[1,0,0],stiffness:.7},{node:16}]}]}
  }};
fs.writeFileSync(path.join(output,'portable-avatar.gltf'),JSON.stringify(avatar,null,2));

// Exercise the real file loader and retargeting APIs, without browser images or a graphics context.
globalThis.ProgressEvent ??= class ProgressEvent { constructor(type,info) { this.type=type;Object.assign(this,info); } };
const targetAvatar=structuredClone(avatar);delete targetAvatar.extensions.VRMC_springBone;targetAvatar.extensionsUsed=['VRMC_vrm'];
targetAvatar.nodes[0].translation=[0,2,0];
targetAvatar.nodes[9].rotation=new THREE.Quaternion().setFromAxisAngle(new THREE.Vector3(0,1,0),-.6).toArray();
const motion=structuredClone(avatar);motion.extensions={VRMC_vrm_animation:{specVersion:'1.0',humanoid:avatar.extensions.VRMC_vrm.humanoid,expressions:{preset:{happy:{node:17}}}}};
motion.extensionsUsed=['VRMC_vrm_animation'];motion.nodes.push({name:'happyWeight'});
const armRest=new THREE.Quaternion().setFromAxisAngle(new THREE.Vector3(0,0,1),.4);
motion.nodes[9].rotation=armRest.toArray();motion.buffers=[];motion.bufferViews=[];motion.accessors=[];
function floatAccessor(values,type,components) {
  const buffer=Buffer.alloc(values.length*4);values.forEach((v,i)=>buffer.writeFloatLE(v,i*4));
  const bi=motion.buffers.length;motion.buffers.push({byteLength:buffer.length,uri:'data:application/octet-stream;base64,'+buffer.toString('base64')});
  const bv=motion.bufferViews.length;motion.bufferViews.push({buffer:bi,byteLength:buffer.length});
  const index=motion.accessors.length;motion.accessors.push({bufferView:bv,componentType:5126,count:values.length/components,type});return index;
}
motion.animations=[{name:'Retarget reference',samplers:[],channels:[]}];
function addTrack(node,path,values) {
  const c=path==='rotation'?4:3,a=motion.animations[0];const sampler=a.samplers.length;
  a.samplers.push({input:floatAccessor([0,1],'SCALAR',1),output:floatAccessor(values,c===4?'VEC4':'VEC3',c),interpolation:'LINEAR'});
  a.channels.push({sampler,target:{node,path}});
}
addTrack(0,'translation',[0,1,0,.25,1.1,.1]);
addTrack(0,'rotation',[0,0,0,1,...new THREE.Quaternion().setFromEuler(new THREE.Euler(.1,.2,-.1)).toArray()]);
addTrack(9,'rotation',[...armRest.toArray(),...armRest.clone().multiply(new THREE.Quaternion().setFromAxisAngle(new THREE.Vector3(1,0,0),.7)).toArray()]);
addTrack(17,'translation',[0,0,0,1,0,0]);
fs.writeFileSync(path.join(output,'retarget-source.gltf'),JSON.stringify(motion,null,2));
fs.writeFileSync(path.join(output,'retarget-avatar.gltf'),JSON.stringify(targetAvatar,null,2));
const avatarLoader=new GLTFLoader().register(parser=>new VRMLoaderPlugin(parser));
const animationLoader=new GLTFLoader().register(parser=>new VRMAnimationLoaderPlugin(parser));
const loadedAvatar=await avatarLoader.parseAsync(JSON.stringify(targetAvatar),'');
const loadedAnimation=await animationLoader.parseAsync(JSON.stringify(motion),'');
const vrm=loadedAvatar.userData.vrm,animation=loadedAnimation.userData.vrmAnimations[0];
const mixer=new THREE.AnimationMixer(vrm.scene),action=mixer.clipAction(createVRMAnimationClip(animation,vrm));
action.setLoop(THREE.LoopOnce,1);action.clampWhenFinished=true;action.play();
const retargetFrames=[];
for(let frame=0;frame<=60;frame++) {
  mixer.setTime(frame/60);vrm.update(0);vrm.scene.updateMatrixWorld(true);
  const boneResults={};for(const [name] of avatarBones.slice(0,15)) { const node=vrm.humanoid.getRawBoneNode(name);boneResults[name]={local:node.matrix.toArray(),global:node.matrixWorld.toArray()}; }
  retargetFrames.push({seconds:frame/60,bones:boneResults,happy:vrm.expressionManager.getValue('happy')});
}
fs.writeFileSync(path.join(output,'retarget.json'),JSON.stringify(retargetFrames));
console.log('Reference: actual VRMA/VRM loaders, 61 retargeted frames with different rest axes and hips heights');

const firstAvatar=structuredClone(targetAvatar);firstAvatar.buffers=[];firstAvatar.bufferViews=[];firstAvatar.accessors=[];
function firstAccessor(values,type,components,componentType=5126) {
  const width=componentType===5126?4:2,bytes=Buffer.alloc(values.length*width);
  values.forEach((v,i)=>componentType===5126?bytes.writeFloatLE(v,i*width):bytes.writeUInt16LE(v,i*width));
  const buffer=firstAvatar.buffers.length,bufferView=firstAvatar.bufferViews.length,index=firstAvatar.accessors.length;
  firstAvatar.buffers.push({byteLength:bytes.length,uri:'data:application/octet-stream;base64,'+bytes.toString('base64')});
  firstAvatar.bufferViews.push({buffer,byteLength:bytes.length});
  firstAvatar.accessors.push({bufferView,componentType,count:values.length/components,type,...(type==='VEC3'?{min:[0,0,0],max:[1,1,1]}:{})});return index;
}
const firstJoints=[],firstWeights=[];
for(let v=0;v<9;v++) { firstJoints.push(0,1,2,0);firstWeights.push(v===1?.999999:v===4?.8:1,v===1?.000001:0,v===4?.2:0,0); }
firstAvatar.meshes=[{primitives:[{attributes:{POSITION:firstAccessor(Array.from({length:27},(_,i)=>i%3===0?(i/3)%2:0),'VEC3',3),
  JOINTS_0:firstAccessor(firstJoints,'VEC4',4,5123),WEIGHTS_0:firstAccessor(firstWeights,'VEC4',4)},indices:firstAccessor(Array.from({length:9},(_,i)=>i),'SCALAR',1,5123)}]}];
firstAvatar.skins=[{joints:[0,2,15]}];
// GLTFLoader marks an entire shared mesh as skinned if any instance has a skin.
// Use a separate rigid mesh so its unskinned instances also remain valid in the reference loader.
firstAvatar.meshes.push(structuredClone(firstAvatar.meshes[0]));
delete firstAvatar.meshes[1].primitives[0].attributes.JOINTS_0;delete firstAvatar.meshes[1].primitives[0].attributes.WEIGHTS_0;
const firstCases=[['auto',true,0],['both',true,0],['firstPersonOnly',true,0],['thirdPersonOnly',true,0],['rigidHead',false,15],['rigidBody',false,0]];
const firstAnnotations=[];
for(const [name,skin,parent] of firstCases) {
  const node=firstAvatar.nodes.length;firstAvatar.nodes.push({name,mesh:skin?0:1,...(skin?{skin:0}:{})});firstAvatar.nodes[parent].children.push(node);
  if(['both','firstPersonOnly','thirdPersonOnly'].includes(name)) firstAnnotations.push({node,type:name});
}
firstAvatar.extensions.VRMC_vrm.firstPerson={meshAnnotations:firstAnnotations};
fs.writeFileSync(path.join(output,'first-person-avatar.gltf'),JSON.stringify(firstAvatar,null,2));
const fpLoaded=await avatarLoader.parseAsync(JSON.stringify(firstAvatar),'');const fpVrm=fpLoaded.userData.vrm;fpVrm.firstPerson.setup();
const fpOutput={};
for(const [name] of firstCases) {
  const draws={firstPerson:[],thirdPerson:[]};fpVrm.scene.traverse(n=>{
    if(!n.isMesh || n.name!==name && n.name!==`${name}(erase)`) return;
    for(const [view,layer] of [['firstPerson',fpVrm.firstPerson.firstPersonOnlyLayer],['thirdPerson',fpVrm.firstPerson.thirdPersonOnlyLayer]]) {
      const camera=new THREE.Layers();camera.enable(layer);
      if(n.layers.test(camera) && n.geometry.index.count>0) draws[view].push(Array.from(n.geometry.index.array));
    }
  });fpOutput[name]=draws;
}
fs.writeFileSync(path.join(output,'first-person.json'),JSON.stringify(fpOutput,null,2));
console.log('Reference: actual VRM first-person setup, mixed head/descendant/zero weights and six view annotations');

const materialAvatar=structuredClone(targetAvatar);materialAvatar.buffers=firstAvatar.buffers;materialAvatar.bufferViews=firstAvatar.bufferViews;materialAvatar.accessors=firstAvatar.accessors;
materialAvatar.materials=[];materialAvatar.meshes=[];
for(let i=0;i<4;i++) {
  const extension={specVersion:'1.0',shadeColorFactor:[.2,.4,.6],matcapFactor:[.7,.8,.9],parametricRimColorFactor:[.1,.2,.3],
    outlineColorFactor:[.3,.2,.1],shadingShiftFactor:-.2,shadingToonyFactor:.75,giEqualizationFactor:.6,rimLightingMixFactor:.2,
    parametricRimFresnelPowerFactor:3,parametricRimLiftFactor:.15,outlineWidthMode:'worldCoordinates',outlineWidthFactor:.01,outlineLightingMixFactor:.4,
    uvAnimationScrollXSpeedFactor:.15,uvAnimationScrollYSpeedFactor:-.2,uvAnimationRotationSpeedFactor:.7,
    transparentWithZWrite:i===2,renderQueueOffsetNumber:i===2?4:i===3?-3:0};
  materialAvatar.materials.push({name:'material'+i,alphaMode:i===0?'OPAQUE':i===1?'MASK':'BLEND',alphaCutoff:.35,doubleSided:i%2===1,
    pbrMetallicRoughness:{baseColorFactor:[.4,.5,.6,.7]},emissiveFactor:[.1,.05,.025],extensions:{KHR_materials_unlit:{},VRMC_materials_mtoon:extension}});
  const primitive=structuredClone(firstAvatar.meshes[1].primitives[0]);primitive.material=i;materialAvatar.meshes.push({primitives:[primitive]});
  const node=materialAvatar.nodes.length;materialAvatar.nodes.push({name:'materialMesh'+i,mesh:i});materialAvatar.nodes[0].children.push(node);
}
materialAvatar.extensionsUsed.push('VRMC_materials_mtoon','KHR_materials_unlit');
fs.writeFileSync(path.join(output,'materials-avatar.gltf'),JSON.stringify(materialAvatar,null,2));
const materialLoaded=await avatarLoader.parseAsync(JSON.stringify(materialAvatar),'');const materialOutput=[];
for(let i=0;i<4;i++) {
  const mesh=materialLoaded.scene.getObjectByName('materialMesh'+i);const m=Array.isArray(mesh.material)?mesh.material[0]:mesh.material;
  const result={alphaTest:m.alphaTest,transparent:m.transparent,depthWrite:m.depthWrite,side:m.side,renderOrder:mesh.renderOrder,
    baseColor:[...m.color.toArray(),m.opacity],emissive:m.emissive.toArray()};
  for(const key of ['shadeColorFactor','matcapFactor','parametricRimColorFactor','outlineColorFactor']) result[key]=m[key].toArray();
  for(const key of ['shadingShiftFactor','shadingToonyFactor','giEqualizationFactor','rimLightingMixFactor','parametricRimFresnelPowerFactor','parametricRimLiftFactor',
      'outlineWidthMode','outlineWidthFactor','outlineLightingMixFactor','uvAnimationScrollXSpeedFactor','uvAnimationScrollYSpeedFactor','uvAnimationRotationSpeedFactor']) result[key]=m[key];
  materialOutput.push(result);
}
fs.writeFileSync(path.join(output,'materials.json'),JSON.stringify(materialOutput,null,2));
console.log('Reference: original MToon loader, explicit colors and four render modes');
