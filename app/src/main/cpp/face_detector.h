#ifndef FACE_DETECTOR_H
#define FACE_DETECTOR_H

#include <vector>
#include <string>
#include <android/asset_manager.h>
#include "ncnn/net.h"
#include "include/ncnn/mat.h"
#include "include/ncnn/net.h"

struct FaceInfo {
    float x;
    float y;
    float w;
    float h;
    float score;
};
class FaceDetector {
public:
    FaceDetector();
    ~FaceDetector();

    int loadModel(AAssetManager* asset_mgr);

    // 只保留实际使用的 detect 函数声明
    int detect(ncnn::Mat &in, std::vector<FaceInfo> &faceList, float score_threshold, int orig_w,
               int orig_h);

private:
    ncnn::Net net;

};

#endif // FACE_DETECTOR_H
