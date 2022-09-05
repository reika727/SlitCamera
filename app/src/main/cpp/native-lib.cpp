#include "image_utility.hpp"
#include <omp.h>
#include <stdexcept>

using YUV = image_utility::YUV;
using RGB = image_utility::RGB;

static jobject image_buffer;
static int counter = 0;

extern "C" JNIEXPORT void JNICALL Java_com_example_slitcamera_MainActivity_jniInitialize(JNIEnv *env, jobject, jint width, jint height)
{
    const jint max_val = env->GetStaticIntField(
            env->FindClass("java/lang/Integer"),
            env->GetStaticFieldID(env->FindClass("java/lang/Integer"), "MAX_VALUE", "I")
    );
    const int used_frames = std::max(width, height);
    try {
        if (width > max_val / height) throw std::overflow_error("overflowed.");
        jint cap = width * height / 2;
        if (cap > max_val / 3) throw std::overflow_error("overflowed.");
        cap *= 3;
        if (cap > max_val / used_frames)throw std::overflow_error("overflowed.");
        cap *= used_frames;
        image_buffer = env->CallStaticObjectMethod(
                env->FindClass("java/nio/ByteBuffer"),
                env->GetStaticMethodID(env->FindClass("java/nio/ByteBuffer"), "allocateDirect", "(I)Ljava/nio/ByteBuffer;"),
                cap
        );
        if (env->ExceptionCheck())throw std::length_error("allocation failed");
        image_buffer = env->NewGlobalRef(image_buffer);
        auto *buffer_ptr = static_cast<uint8_t *>(env->GetDirectBufferAddress(image_buffer));
        for (int i = 0; i < used_frames; ++i)
            for (int j = 0; j < width * height / 2; ++j)
                buffer_ptr[(i * 3 + 2) * width * height / 2 + j] = 128;
    } catch (const std::overflow_error &e) {
        env->ThrowNew(env->FindClass("java/lang/ArithmeticException"), e.what());
    } catch (const std::length_error &) {
        auto e = env->ExceptionOccurred();
        env->ExceptionClear();
        env->Throw(e);
        env->DeleteLocalRef(e);
    }
}

extern "C" JNIEXPORT void JNICALL Java_com_example_slitcamera_MainActivity_jniSlitScan(
        JNIEnv *env, jobject,
        jobject image, jobject drawing_surface, jboolean is_landscape
)
{
    try {
        image_utility::image_accessor_YUV_420_888 ia(env, image);
        image_utility::surface_texture_accessor_R8G8B8X8 sa(env, drawing_surface);
        image_utility::coordinate_transformer ct(ia, sa, is_landscape);
        const int used_frames = std::max(ia.get_height(), ia.get_width());
        auto *buffer_ptr = static_cast<uint8_t *>(env->GetDirectBufferAddress(image_buffer));
        auto access_buffer = [ia, buffer_ptr, used_frames](int x, int y, YUV c, int back) -> uint8_t & {
            back %= used_frames;
            return buffer_ptr[
                    (counter - back + used_frames) % used_frames * ia.get_width() * ia.get_height() * 3 / 2 +
                    (
                            c == YUV::Y ? y * ia.get_width() + x :
                            ia.get_height() * ia.get_width() + (y >> 1) * ia.get_width() + (x >> 1 << 1) + (c == YUV::V ? 0 : 1)
                    )
            ];
        };
#pragma omp parallel for default(none) shared(ia, access_buffer, is_landscape, ct, sa) collapse(2)
        for (int y = 0; y < ia.get_height(); ++y) {
            for (int x = 0; x < ia.get_width(); ++x) {
                access_buffer(x, y, YUV::Y, 0) = ia(x, y, YUV::Y);
                access_buffer(x, y, YUV::U, 0) = ia(x, y, YUV::U);
                access_buffer(x, y, YUV::V, 0) = ia(x, y, YUV::V);
                int back = is_landscape ? y : x;
                auto rgb = image_utility::YUV_to_RGB::convert(
                        access_buffer(x, y, YUV::Y, back),
                        access_buffer(x, y, YUV::U, back) - 128,
                        access_buffer(x, y, YUV::V, back) - 128
                );
                auto c = ct(x, y);
                sa(c.x, c.y, RGB::R) = static_cast<uint8_t>(rgb.R);
                sa(c.x, c.y, RGB::G) = static_cast<uint8_t>(rgb.G);
                sa(c.x, c.y, RGB::B) = static_cast<uint8_t>(rgb.B);
            }
        }
        ++counter %= used_frames;
    } catch (std::runtime_error &) {
        return;
    }
}

extern "C" JNIEXPORT void JNICALL Java_com_example_slitcamera_MainActivity_jniFinalize(JNIEnv *env, jobject)
{
    env->DeleteGlobalRef(image_buffer);
    counter = 0;
}