#ifndef GIF_H_
#define GIF_H_

#include <jni.h>

#include "image.h"
#include "giflib/gif_lib.h"

#define DEFAULT_DELAY 100

typedef struct {
    GifFileType* gifFile;
    int* trans;
    int* disposals;
    int format;
    void* pixels;
    int pxlIndex;
    void* bak;
    int bakIndex;
} GIF;

jobject GIF_DecodeStream(JNIEnv* env, jobject is, jint format);
jobject GIF_DecodeFileHandler(JNIEnv* env, FILE* fp, jint format);
void GIF_Render(JNIEnv* env, GIF* gif, int format, int index);
void GIF_Free(JNIEnv* env, GIF* gif);

#endif /* GIF_H_ */
