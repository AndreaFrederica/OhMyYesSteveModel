// Independent reference executable; it calls Saba rather than YSM's Java evaluator.
#include <Saba/Model/MMD/PMXModel.h>
#include <Saba/Model/MMD/PMDModel.h>
#include <Saba/Model/MMD/VMDAnimation.h>
#include <Saba/Model/MMD/VMDFile.h>
#include <fstream>
#include <iomanip>
#include <iostream>
#include <memory>
#include <string>

int main(int argc,char** argv) {
    if(argc!=6) { std::cerr<<"model.pmx motion.vmd output.csv frames physics(0/1)\n";return 1; }
    std::string path=argv[1];std::shared_ptr<saba::MMDModel> model;
    if(path.size()>=4 && path.substr(path.size()-4)==".pmd") {
        auto pmd=std::make_shared<saba::PMDModel>();if(!pmd->Load(path,"")) return 2;model=pmd;
    } else {
        auto pmx=std::make_shared<saba::PMXModel>();if(!pmx->Load(path,"")) return 2;model=pmx;
    }
    model->SetParallelUpdateHint(1);
    saba::VMDFile motion;if(!saba::ReadVMDFile(&motion,argv[2])) return 3;
    saba::VMDAnimation animation;if(!animation.Create(model) || !animation.Add(motion)) return 4;
    model->InitializeAnimation();int frames=std::stoi(argv[4]);bool physics=std::stoi(argv[5])!=0;
    std::ofstream out(argv[3]);out<<std::setprecision(9)<<"frame,type,index";
    for(int k=0;k<16;k++) out<<",v"<<k;out<<"\n";
    for(int frame=0;frame<=frames;frame++) {
        model->BeginAnimation();animation.Evaluate(frame*.5f);model->UpdateMorphAnimation();model->UpdateNodeAnimation(false);
        if(physics && frame>0) model->UpdatePhysicsAnimation(1.f/60);
        model->UpdateNodeAnimation(true);model->EndAnimation();model->Update();
        auto nodes=model->GetNodeManager();
        for(size_t i=0;i<nodes->GetNodeCount();i++) {
            const auto& m=nodes->GetMMDNode(i)->GetGlobalTransform();out<<frame<<",bone,"<<i;
            // Saba converts source LH to RH; conjugate Z to compare in the source's MMD coordinates.
            for(int c=0;c<4;c++) for(int r=0;r<4;r++) out<<","<<m[c][r]*((c==2)^(r==2)?-1:1);
            out<<"\n";
        }
        for(size_t i=0;i<model->GetVertexCount();i++) {
            const auto& p=model->GetUpdatePositions()[i];const auto& n=model->GetUpdateNormals()[i];
            out<<frame<<",vertex,"<<i<<","<<p.x<<","<<p.y<<","<<-p.z<<","<<n.x<<","<<n.y<<","<<-n.z;
            for(int k=6;k<16;k++) out<<",0";out<<"\n";
        }
        for(size_t i=0;i<model->GetMaterialCount();i++) {
            const auto& m=model->GetMaterials()[i];out<<frame<<",material,"<<i;
            out<<","<<m.m_diffuse.x<<","<<m.m_diffuse.y<<","<<m.m_diffuse.z<<","<<m.m_alpha;
            out<<","<<m.m_specular.x<<","<<m.m_specular.y<<","<<m.m_specular.z<<","<<m.m_specularPower;
            out<<","<<m.m_ambient.x<<","<<m.m_ambient.y<<","<<m.m_ambient.z;
            for(int k=11;k<16;k++) out<<",0";out<<"\n";
        }
    }
    return out?0:5;
}
