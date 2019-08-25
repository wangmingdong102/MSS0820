package com.android.launcher3.mss;
/* mssflexiblescreen */
import android.animation.TimeInterpolator;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;

import com.android.launcher3.CellLayout;
import com.android.launcher3.Workspace;

public class PageViewAnimation {
    private static final String TAG = "PageViewAnimation";

    public static final int PAGEVIEW_ANIMATION_NORMAL = 0;
    //经典动画
    public static final int PAGEVIEW_ANIMATION_TURNTABLE = 1;
    // 转盘动画
    public static final int PAGEVIEW_ANIMATION_LAYERED = 2;
    //层叠动画
    public static final int PAGEVIEW_ANIMATION_ROTATE = 3;
    //旋转动画
    public static final int PAGEVIEW_ANIMATION_PAGETURN = 4;
    //翻页动画
    public static final int PAGEVIEW_ANIMATION_ROTATEBYLEFTTOPPOINT = 5;
    // 绕左上角旋转的动画
    public static final int PAGEVIEW_ANIMATION_ROTATEBYCENTERPOINT = 6;
    // 绕中心点旋转的动画
    public static final int PAGEVIEW_ANIMATION_BLOCKS = 7;
    // 设置方块动画

    public static final int PAGEVIEW_ANIMATION_ZOOM_IN = 8;//放大
    public static final int PAGEVIEW_ANIMATION_ZOOM_OUT = 9;//缩小
    public static final int PAGEVIEW_ANIMATION_ROTATE_UP = 10;//顺时针旋转90度
    public static final int PAGEVIEW_ANIMATION_ROTATE_DOWN = 11;//逆时针旋转90度
    public static final int PAGEVIEW_ANIMATION_CUBE_IN = 12;//开门
    public static final int PAGEVIEW_ANIMATION_CUBE_OUT = 13;//关门
    public static final int PAGEVIEW_ANIMATION_STACK = 14;//靠近
    public static final int PAGEVIEW_ANIMATION_ACCORDION = 15;//压缩
    public static final int PAGEVIEW_ANIMATION_FLIP = 916;//翻页
    public static final int PAGEVIEW_ANIMATION_CYLINDER_IN = 16;//
    public static final int PAGEVIEW_ANIMATION_CYLINDER_OUT = 17;//
    public static final int PAGEVIEW_ANIMATION_CROSSFADE = 18;//透明度
    public static final int PAGEVIEW_ANIMATION_OVERVIEW = 19;//透明度＋缩放

    public static final int PAGEVIEW_ANIMATION_FIRST = PAGEVIEW_ANIMATION_NORMAL;
    public static final int PAGEVIEW_ANIMATION_LAST = PAGEVIEW_ANIMATION_OVERVIEW;

    public static String getPageVIewAnimationName(int id){
        switch (id){
            case PAGEVIEW_ANIMATION_NORMAL:
                return "平滑移动";
            case PAGEVIEW_ANIMATION_TURNTABLE:
                return "转盘";
            case PAGEVIEW_ANIMATION_LAYERED:
                return "层叠";
            case PAGEVIEW_ANIMATION_ROTATE:
                return "旋转";
            case PAGEVIEW_ANIMATION_PAGETURN:
                return "翻页";
            case PAGEVIEW_ANIMATION_ROTATEBYLEFTTOPPOINT:
                return "左上角旋转";
            case PAGEVIEW_ANIMATION_ROTATEBYCENTERPOINT:
                return "中心旋转";
            case PAGEVIEW_ANIMATION_BLOCKS:
                return "方块动画";
            case PAGEVIEW_ANIMATION_ZOOM_IN:
                return "放大";
            case PAGEVIEW_ANIMATION_ZOOM_OUT:
                return "缩小";
            case PAGEVIEW_ANIMATION_ROTATE_UP:
                return "顺时针旋转90度";
            case PAGEVIEW_ANIMATION_ROTATE_DOWN:
                return "逆时针旋转90度";
            case PAGEVIEW_ANIMATION_CUBE_IN:
                return "开门";
            case PAGEVIEW_ANIMATION_CUBE_OUT:
                return "关门";
            case PAGEVIEW_ANIMATION_STACK:
                return "靠近";
            default:
                    return "" + id;

        }
    }

    private static ZInterpolator mZInterpolator = new ZInterpolator(0.5f);
    private static AccelerateInterpolator mAlphaInterpolator = new AccelerateInterpolator(0.9f);
    private static DecelerateInterpolator mLeftScreenAlphaInterpolator = new DecelerateInterpolator(4);
    private static AccelerateDecelerateInterpolator mScaleInterpolator = new AccelerateDecelerateInterpolator();
    private static PageViewAnimation mInstance;
    private static float CAMERA_DISTANCE = 6500;
    private static float TRANSITION_SCALE_FACTOR = 0.74f;
    private static float TRANSITION_PIVOT = 0.65f;
    private static float TRANSITION_MAX_ROTATION = 24;
    private static float TRANSITION_SCREEN_ROTATION = 90;

    private float mTranslationX = 0f;
    private float mScale = 1f;
    private float mScaleX = 1f;
    private float mScaleY = 1f;
    private float mAlpha = 1f;
    private float mRotation = 0f;
    private float mRotationY = 0f;
    private float mPivotX = 0f;
    private float mPivotY = 0f;
    private int mAnimaType = PAGEVIEW_ANIMATION_NORMAL;

    public static PageViewAnimation getInstance() {
        if (mInstance == null) {
            synchronized (PageViewAnimation.class) {
                if (mInstance == null) {
                    mInstance = new PageViewAnimation();
                }
            }
        }
        return mInstance;
    }

    public void setPageViewAnime(int type) {
        mAnimaType = type;
    }

    public int getPageViewAnime() {
        return mAnimaType;
    }

    public void pageViewAnime(float scrollProgress, int i, int count, float density, View v, Workspace ws) {
        resetAttr(v);
        switch (mAnimaType) {
            case PAGEVIEW_ANIMATION_TURNTABLE:
                mRotation = -TRANSITION_MAX_ROTATION * scrollProgress;
                mPivotX = v.getMeasuredWidth() * 0.5f;
                mPivotY = v.getMeasuredHeight();
                break;
            case PAGEVIEW_ANIMATION_LAYERED: {
                float minScrollProgress = Math.min(0, scrollProgress);
                float interpolatedProgress;
                mTranslationX = minScrollProgress * v.getMeasuredWidth();
                interpolatedProgress = mZInterpolator.getInterpolation(Math.abs(minScrollProgress));
                mScale = (1 - interpolatedProgress) + interpolatedProgress * TRANSITION_SCALE_FACTOR;
                mScaleX = mScale;
                mScaleY = mScale;
                if ((scrollProgress < 0)) {
                    mAlpha = mAlphaInterpolator.getInterpolation(1 - Math.abs(scrollProgress));
                } else {
                    mAlpha = mLeftScreenAlphaInterpolator.getInterpolation(1 - scrollProgress);
                }
            }
            break;
            case PAGEVIEW_ANIMATION_ROTATE:
                mPivotX = v.getMeasuredWidth() * 0.5f;
                mPivotY = v.getMeasuredHeight();
                mRotationY = -90 * scrollProgress;
                mTranslationX = scrollProgress * v.getMeasuredWidth();
                mAlpha = mAlphaInterpolator.getInterpolation(1 - Math.abs(scrollProgress));
                break;
            case PAGEVIEW_ANIMATION_PAGETURN:
                mPivotX = 0.0f;
                mPivotY = 0.0f;
                mRotationY = -90 * scrollProgress;
                mTranslationX = scrollProgress * v.getMeasuredWidth();
                mAlpha = mAlphaInterpolator.getInterpolation(1 - Math.abs(scrollProgress));
                break;
            case PAGEVIEW_ANIMATION_ROTATEBYLEFTTOPPOINT:
                mPivotX = 0.0f;
                mPivotY = 0.0f;
                mRotation = -90 * scrollProgress;
                mTranslationX = scrollProgress * v.getMeasuredWidth();
                mAlpha = mAlphaInterpolator.getInterpolation(1 - Math.abs(scrollProgress));
                break;
            case PAGEVIEW_ANIMATION_ROTATEBYCENTERPOINT:
                mPivotX = v.getMeasuredWidth() * 0.5f;
                mPivotY = v.getMeasuredHeight() * 0.5f;
                mRotation = -90 * scrollProgress;
                mTranslationX = scrollProgress * v.getMeasuredWidth();
                mAlpha = mAlphaInterpolator.getInterpolation(1 - Math.abs(scrollProgress));
                break;
            case PAGEVIEW_ANIMATION_BLOCKS:
                if (scrollProgress < 0) {
                    mPivotX = 0.0f;
                } else {
                    mPivotX = v.getMeasuredWidth();
                }
                mRotationY = -90 * scrollProgress;
                break;

            case PAGEVIEW_ANIMATION_ZOOM_IN:
            case PAGEVIEW_ANIMATION_ZOOM_OUT:
                boolean zoomIn = false;
                zoomIn = (PAGEVIEW_ANIMATION_ZOOM_IN == mAnimaType);
                mScale = 1.0f + (zoomIn ? -0.8f : 0.4f) * Math.abs(scrollProgress);
                mScaleX = mScale;
                mScaleY = mScale;
                // Extra translation to account for the increase in size
                if (!zoomIn) {
                    mTranslationX = v.getMeasuredWidth() * 0.1f * -scrollProgress;
                }
                break;
            case PAGEVIEW_ANIMATION_ROTATE_UP:
            case PAGEVIEW_ANIMATION_ROTATE_DOWN:
                boolean rotateUp = (PAGEVIEW_ANIMATION_ROTATE_UP == mAnimaType);
                mRotation =
                        (rotateUp ? TRANSITION_SCREEN_ROTATION : -TRANSITION_SCREEN_ROTATION) * scrollProgress;

                mTranslationX = v.getMeasuredWidth() * scrollProgress;

                float rotatePoint =
                        (v.getMeasuredWidth() * 0.5f) /
                                (float) Math.tan(Math.toRadians((double) (TRANSITION_SCREEN_ROTATION * 0.5f)));

                mPivotX = (v.getMeasuredWidth() * 0.5f);
                if (rotateUp) {
                    mPivotY = -rotatePoint;
                } else {
                    mPivotY = v.getMeasuredHeight() + rotatePoint;
                }

                break;

            case PAGEVIEW_ANIMATION_CUBE_IN:
            case PAGEVIEW_ANIMATION_CUBE_OUT:
                boolean cubeIn = (PAGEVIEW_ANIMATION_CUBE_IN == mAnimaType);
                mRotationY = (cubeIn ? 90.0f : -90.0f) * scrollProgress;
                mPivotY = scrollProgress < 0 ? 0 : v.getMeasuredWidth();
                mPivotY = v.getMeasuredHeight() * 0.5f;
                break;

            case PAGEVIEW_ANIMATION_STACK: {
                final boolean isRtl = ws.isLayoutRtl();
                float interpolatedProgress;
                float maxScrollProgress = Math.max(0, scrollProgress);
                float minScrollProgress = Math.min(0, scrollProgress);

                if (isRtl) {
                    mTranslationX = maxScrollProgress * v.getMeasuredWidth();
                    interpolatedProgress = mZInterpolator.getInterpolation(Math.abs(maxScrollProgress));
                } else {
                    mTranslationX = minScrollProgress * v.getMeasuredWidth();
                    interpolatedProgress = mZInterpolator.getInterpolation(Math.abs(minScrollProgress));
                }
                mScale = (1 - interpolatedProgress) +
                        interpolatedProgress * TRANSITION_SCALE_FACTOR;
                mScaleX = mScale;
                mScaleY = mScale;

                if (isRtl && (scrollProgress > 0)) {
                    mAlpha = mAlphaInterpolator.getInterpolation(1 - Math.abs(maxScrollProgress));
                } else if (!isRtl && (scrollProgress < 0)) {
                    mAlpha = mAlphaInterpolator.getInterpolation(1 - Math.abs(scrollProgress));
                } else {
                    //  On large screens we need to fade the page as it nears its leftmost position
                    mAlpha = mLeftScreenAlphaInterpolator.getInterpolation(1 - scrollProgress);
                }
            }
                break;

            case PAGEVIEW_ANIMATION_ACCORDION:
                mScaleX = 1.0f - Math.abs(scrollProgress);
                mPivotX = scrollProgress < 0 ? 0 : v.getMeasuredWidth();
                mPivotY = v.getMeasuredHeight() / 2f;
                break;

            case PAGEVIEW_ANIMATION_FLIP:
                mRotationY = -180.0f * Math.max(-1f, Math.min(1f, scrollProgress));
                //v.setCameraDistance(density * CAMERA_DISTANCE);
                mPivotX = v.getMeasuredWidth() * 0.5f;
                mPivotY = v.getMeasuredHeight() * 0.5f;
                if (scrollProgress >= -0.5f && scrollProgress <= 0.5f) {
                    mTranslationX = v.getMeasuredWidth() * scrollProgress;
                } else {
                    mTranslationX = 0f;
                }
                break;

            case PAGEVIEW_ANIMATION_CYLINDER_IN:
            case PAGEVIEW_ANIMATION_CYLINDER_OUT:
                boolean cylinderIn = (PAGEVIEW_ANIMATION_CYLINDER_IN == mAnimaType);
                mRotationY = (cylinderIn ? TRANSITION_SCREEN_ROTATION : -TRANSITION_SCREEN_ROTATION) * scrollProgress;

                mPivotX = ((scrollProgress + 1) * v.getMeasuredWidth() * 0.5f);
                mPivotY = (v.getMeasuredHeight() * 0.5f);
                break;

            case PAGEVIEW_ANIMATION_CROSSFADE:
                mAlpha = 1 - Math.abs(scrollProgress);
                mPivotX = v.getMeasuredWidth() * 0.5f;
                mPivotY = (v.getMeasuredHeight() * 0.5f);
                break;

            case PAGEVIEW_ANIMATION_OVERVIEW:
                mScale = 1.0f - 0.1f *
                        mScaleInterpolator.getInterpolation(Math.min(0.3f, Math.abs(scrollProgress)) / 0.3f);
                mScaleX = mScale;
                mScaleY = mScale;

                mPivotX = scrollProgress < 0 ? 0 : v.getMeasuredWidth();
                mPivotY = (v.getMeasuredHeight() * 0.5f);
                //ws.setChildAlpha(v, mScale);
                break;

            case PAGEVIEW_ANIMATION_NORMAL:
            default:
                break;
        }
        overScrollAnimation(scrollProgress, i, count, density, v);
        setViewAttr(v);
        showOrHideView(v);
    }
    /**
     * 设置转盘动画
     *
             * @param scrollProgress
     * @param view
     */
    public void setTurntableAnim(float scrollProgress, int i, int count, float density, View v) {
        resetAttr(v);
        mRotation = -TRANSITION_MAX_ROTATION * scrollProgress;
        mPivotX = v.getMeasuredWidth() * 0.5f;
        mPivotY = v.getMeasuredHeight();
        overScrollAnimation(scrollProgress, i, count, density, v);
        setViewAttr(v);
        showOrHideView(v);
    }

     /**
     * 设置层叠动画
     *
     * @param scrollProgress
     * @param density
     * @param v
     */
     public void setLayeredAnim(float scrollProgress, int i, int count, float density, View v) {
         resetAttr(v);
         float minScrollProgress = Math.min(0, scrollProgress);
         float interpolatedProgress;
         mTranslationX = minScrollProgress * v.getMeasuredWidth();
         interpolatedProgress = mZInterpolator.getInterpolation(Math.abs(minScrollProgress));
         mScale = (1 - interpolatedProgress) + interpolatedProgress * TRANSITION_SCALE_FACTOR;
         if ((scrollProgress < 0)) {
             mAlpha = mAlphaInterpolator.getInterpolation(1 - Math.abs(scrollProgress));
         } else {
             mAlpha = mLeftScreenAlphaInterpolator.getInterpolation(1 - scrollProgress);
         }
         overScrollAnimation(scrollProgress, i, count, density, v);
         setViewAttr(v);
         showOrHideView(v);
     }

     /**
     * 经典模式,水平左出右进
     * @param scrollProgress
     * @param i
     * @param count
     * @param density
     * @param v
     */
     public void setNormalAnim(float scrollProgress, int i, int count, float density, View v) {
         resetAttr(v);
         overScrollAnimation(scrollProgress, i, count, density, v);
         setViewAttr(v);
         showOrHideView(v);
     }
     /**
     * 设置旋转动画
     * @param scrollProgress
     * @param i
     * @param count
     * @param density
     * @param v
     */
     public void setRotateAnim(float scrollProgress, int i, int count, float density, View v) {
         resetAttr(v);
         mPivotX = v.getMeasuredWidth() * 0.5f;
         mPivotY = v.getMeasuredHeight();
         mRotationY = -90 * scrollProgress;
         mTranslationX = scrollProgress * v.getMeasuredWidth();
         mAlpha = mAlphaInterpolator.getInterpolation(1 - Math.abs(scrollProgress));
         overScrollAnimation(scrollProgress, i, count, density, v);
         setViewAttr(v);
         showOrHideView(v);
     }
     /**
     * 设置翻页动画
     * @param scrollProgress
     * @param i
     * @param count
     * @param density
     * @param v
     */
     public void setPageTurnAnim(float scrollProgress, int i, int count, float density, View v) {
         resetAttr(v);
         mPivotX = 0.0f;
         mPivotY = 0.0f;
         mRotationY = -90 * scrollProgress;
         mTranslationX = scrollProgress * v.getMeasuredWidth();
         mAlpha = mAlphaInterpolator.getInterpolation(1 - Math.abs(scrollProgress));
         overScrollAnimation(scrollProgress, i, count, density, v);
         setViewAttr(v);
         showOrHideView(v);
     }
     /**
     * 设置以左上角为中心点的旋转
     * @param scrollProgress
     * @param i
     * @param count
     * @param density
     * @param v
     */
     public void setRotateByLeftTopPointAnim(float scrollProgress, int i, int count, float density, View v) {
         resetAttr(v);
         mPivotX = 0.0f;
         mPivotY = 0.0f;
         mRotation = -90 * scrollProgress;
         mTranslationX = scrollProgress * v.getMeasuredWidth();
         mAlpha = mAlphaInterpolator.getInterpolation(1 - Math.abs(scrollProgress));
         overScrollAnimation(scrollProgress, i, count, density, v);
         setViewAttr(v);
         showOrHideView(v);
     }
     /**
     * 设置以正中心为中心点的旋转
     * @param scrollProgress
     * @param i
     * @param count
     * @param density
     * @param v
     */
     public void setRotateByCenterPointAnim(float scrollProgress, int i, int count, float density, View v) {
         resetAttr(v);
         mPivotX = v.getMeasuredWidth() * 0.5f;
         mPivotY = v.getMeasuredHeight() * 0.5f;
         mRotation = -90 * scrollProgress;
         mTranslationX = scrollProgress * v.getMeasuredWidth();
         mAlpha = mAlphaInterpolator.getInterpolation(1 - Math.abs(scrollProgress));
         overScrollAnimation(scrollProgress, i, count, density, v);
         setViewAttr(v);
         showOrHideView(v);
     }
     /**
     * 设置方块动画的旋转
     * @param scrollProgress
     * @param i
     * @param count
     * @param density
     * @param v
     */
     public void setBlocksAnim(float scrollProgress, int i, int count, float density, View v) {
         resetAttr(v);
         if (scrollProgress < 0) {
             mPivotX = 0.0f;
         } else {
             mPivotX = v.getMeasuredWidth();
         }
         mRotationY = -90 * scrollProgress;
         overScrollAnimation(scrollProgress, i, count, density, v);
         setViewAttr(v);
         showOrHideView(v);
     }
     /**
     * 设置View的属性
     * @param v
     */
     private void setViewAttr(View v) {
         v.setPivotY(mPivotY);
         v.setPivotX(mPivotX);
         v.setRotation(mRotation);
         v.setRotationY(mRotationY);
         v.setTranslationX(mTranslationX);
         v.setScaleX(mScaleX);
         v.setScaleY(mScaleY);
         v.setAlpha(mAlpha);
     }
     /**
     * 根据alpha的值来判断是否显示view
     *
     * @param alpha
     * @param v
     */
     private void showOrHideView(View v) {
         if (mAlpha == 0) {
             v.setVisibility(View.INVISIBLE);
         } else if (v.getVisibility() != View.VISIBLE) {
             v.setVisibility(View.VISIBLE);
         }
     }
     /**
     * 重置属性
      */
     private void resetAttr(View v) {
         mTranslationX = 0f;
         mScale = 1f;
         mScaleX = 1.0f;
         mScaleY = 1.0f;
         mAlpha = 1f;
         mRotation = 0f;
         mRotationY = 0f;
         mPivotX = v.getMeasuredWidth() * 0.5f;
         mPivotY = v.getMeasuredHeight() * 0.5f;
     }
     /**
     * 设置pageView左右两端时的动画
     *
     * @param scrollProgress
     * @param i
     * @param count
     * @param density
     * @param v
     */
     public void overScrollAnimation(float scrollProgress, int i, int count, float density, View v) {
         float xPivot = TRANSITION_PIVOT;
         boolean isOverscrollingFirstPage = scrollProgress < 0;
         boolean isOverscrollingLastPage = scrollProgress > 0;
         int pageWidth = v.getMeasuredWidth();
         int pageHeight = v.getMeasuredHeight();
         v.setCameraDistance(density * CAMERA_DISTANCE);
         if (i == 0 && isOverscrollingFirstPage) {
             mPivotX = xPivot * pageWidth;
             mPivotY = pageHeight / 2.0f;
             mRotation = 0f;
             mRotationY = -TRANSITION_MAX_ROTATION * scrollProgress;
             mScale = 1.0f;
             mAlpha = 1.0f;
             mTranslationX = 0f;
         } else if (i == count - 1 && isOverscrollingLastPage) {
             mPivotX = xPivot * pageWidth;
             mPivotY = pageHeight / 2.0f;
             mRotation = 0f;
             mRotationY = -TRANSITION_MAX_ROTATION * scrollProgress;
             mScale = 1.0f;
             mAlpha = 1.0f;
             mTranslationX = 0f;
         }
     }

    private static class ZInterpolator implements TimeInterpolator {
        private float focalLength;

        public ZInterpolator(float foc) {
            focalLength = foc;
        }

        public float getInterpolation(float input) {
            return (1.0f - focalLength / (focalLength + input)) / (1.0f - focalLength / (focalLength + 1.0f));
        }
    }
}
