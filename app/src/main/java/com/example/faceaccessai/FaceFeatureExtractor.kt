package com.example.faceaccessai

import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import kotlin.math.sqrt


object FaceFeatureExtractor {

    /*
     * =========================================================
     * KẾT QUẢ ĐẶC TRƯNG KHUÔN MẶT
     * =========================================================
     *
     * Chưa phân loại OPEN / CLOSED ở đây.
     *
     * File này chỉ có nhiệm vụ tính các giá trị thô:
     *
     * - EAR mắt trái
     * - EAR mắt phải
     * - EAR trung bình
     * - MAR
     *
     * Threshold sẽ được xử lý ở bước sau.
     */
    data class FaceFeatures(
        val leftEAR: Float,
        val rightEAR: Float,
        val averageEAR: Float,
        val mar: Float
    )


    /*
     * =========================================================
     * HÀM CHÍNH
     * =========================================================
     */
    fun extract(
        landmarks: List<NormalizedLandmark>,
        imageWidth: Int,
        imageHeight: Int
    ): FaceFeatures? {

        /*
         * Face Landmarker hiện tại của chúng ta trả 478 điểm.
         *
         * Các index đang sử dụng đều nhỏ hơn 478.
         */
        if (landmarks.size < 478) {
            return null
        }


        /*
         * EAR mắt phải.
         *
         * Các điểm:
         *
         * 33  = góc ngoài
         * 133 = góc trong
         *
         * 160, 144 = cặp trên / dưới
         * 158, 153 = cặp trên / dưới
         */
        val rightEAR =
            calculateEAR(
                landmarks = landmarks,

                corner1Index = 33,
                upper1Index = 160,
                upper2Index = 158,

                corner2Index = 133,
                lower2Index = 153,
                lower1Index = 144,

                imageWidth = imageWidth,
                imageHeight = imageHeight
            )


        /*
         * EAR mắt trái.
         *
         * 362 và 263 là hai góc mắt.
         *
         * 385 / 380
         * 387 / 373
         *
         * là hai cặp điểm trên / dưới.
         */
        val leftEAR =
            calculateEAR(
                landmarks = landmarks,

                corner1Index = 362,
                upper1Index = 385,
                upper2Index = 387,

                corner2Index = 263,
                lower2Index = 373,
                lower1Index = 380,

                imageWidth = imageWidth,
                imageHeight = imageHeight
            )


        /*
         * EAR trung bình của hai mắt.
         */
        val averageEAR =
            (leftEAR + rightEAR) / 2f


        /*
         * MAR của miệng.
         */
        val mar =
            calculateMAR(
                landmarks = landmarks,
                imageWidth = imageWidth,
                imageHeight = imageHeight
            )


        return FaceFeatures(
            leftEAR = leftEAR,
            rightEAR = rightEAR,
            averageEAR = averageEAR,
            mar = mar
        )
    }


    /*
     * =========================================================
     * EAR - EYE ASPECT RATIO
     * =========================================================
     *
     * Công thức:
     *
     *             d(P2,P6) + d(P3,P5)
     * EAR = --------------------------------
     *                  2 × d(P1,P4)
     *
     * Khi mắt mở:
     * khoảng cách dọc lớn hơn.
     *
     * Khi mắt nhắm:
     * khoảng cách dọc giảm xuống.
     */
    private fun calculateEAR(
        landmarks: List<NormalizedLandmark>,

        corner1Index: Int,

        upper1Index: Int,
        upper2Index: Int,

        corner2Index: Int,

        lower2Index: Int,
        lower1Index: Int,

        imageWidth: Int,
        imageHeight: Int
    ): Float {

        val corner1 =
            point(
                landmarks[corner1Index],
                imageWidth,
                imageHeight
            )

        val upper1 =
            point(
                landmarks[upper1Index],
                imageWidth,
                imageHeight
            )

        val upper2 =
            point(
                landmarks[upper2Index],
                imageWidth,
                imageHeight
            )

        val corner2 =
            point(
                landmarks[corner2Index],
                imageWidth,
                imageHeight
            )

        val lower2 =
            point(
                landmarks[lower2Index],
                imageWidth,
                imageHeight
            )

        val lower1 =
            point(
                landmarks[lower1Index],
                imageWidth,
                imageHeight
            )


        val verticalDistance1 =
            distance(
                upper1,
                lower1
            )


        val verticalDistance2 =
            distance(
                upper2,
                lower2
            )


        val horizontalDistance =
            distance(
                corner1,
                corner2
            )


        /*
         * Tránh chia cho 0 nếu frame lỗi.
         */
        if (horizontalDistance <= 0.0001f) {
            return 0f
        }


        return (
                verticalDistance1 +
                        verticalDistance2
                ) / (
                2f * horizontalDistance
                )
    }


    /*
     * =========================================================
     * MAR - MOUTH ASPECT RATIO
     * =========================================================
     *
     * Hai góc miệng:
     *
     * 78  ----------------  308
     *
     * Các khoảng cách dọc:
     *
     * 82  ↕ 87
     * 13  ↕ 14
     * 312 ↕ 317
     *
     * Khi há miệng:
     * các khoảng cách dọc tăng.
     */
    private fun calculateMAR(
        landmarks: List<NormalizedLandmark>,
        imageWidth: Int,
        imageHeight: Int
    ): Float {

        val leftCorner =
            point(
                landmarks[78],
                imageWidth,
                imageHeight
            )


        val rightCorner =
            point(
                landmarks[308],
                imageWidth,
                imageHeight
            )


        val upperLeft =
            point(
                landmarks[82],
                imageWidth,
                imageHeight
            )


        val lowerLeft =
            point(
                landmarks[87],
                imageWidth,
                imageHeight
            )


        val upperCenter =
            point(
                landmarks[13],
                imageWidth,
                imageHeight
            )


        val lowerCenter =
            point(
                landmarks[14],
                imageWidth,
                imageHeight
            )


        val upperRight =
            point(
                landmarks[312],
                imageWidth,
                imageHeight
            )


        val lowerRight =
            point(
                landmarks[317],
                imageWidth,
                imageHeight
            )


        val verticalLeft =
            distance(
                upperLeft,
                lowerLeft
            )


        val verticalCenter =
            distance(
                upperCenter,
                lowerCenter
            )


        val verticalRight =
            distance(
                upperRight,
                lowerRight
            )


        val mouthWidth =
            distance(
                leftCorner,
                rightCorner
            )


        if (mouthWidth <= 0.0001f) {
            return 0f
        }


        /*
         * Trung bình 3 khoảng cách dọc
         * chia cho chiều rộng miệng.
         */
        return (
                verticalLeft +
                        verticalCenter +
                        verticalRight
                ) / (
                3f * mouthWidth
                )
    }


    /*
     * =========================================================
     * NORMALIZED LANDMARK → PIXEL
     * =========================================================
     */
    private fun point(
        landmark: NormalizedLandmark,
        imageWidth: Int,
        imageHeight: Int
    ): Point2D {

        return Point2D(
            x = landmark.x() * imageWidth.toFloat(),
            y = landmark.y() * imageHeight.toFloat()
        )
    }


    /*
     * =========================================================
     * EUCLIDEAN DISTANCE
     * =========================================================
     */
    private fun distance(
        point1: Point2D,
        point2: Point2D
    ): Float {

        val dx =
            point1.x - point2.x

        val dy =
            point1.y - point2.y


        return sqrt(
            dx * dx +
                    dy * dy
        )
    }


    /*
     * Tọa độ 2D đơn giản.
     */
    private data class Point2D(
        val x: Float,
        val y: Float
    )
}