package com.example.faceaccessai

import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sqrt


object FaceFeatureExtractor {

    // Các đặc trưng được tính từ khuôn mặt
    data class FaceFeatures(
        val leftEAR: Float,
        val rightEAR: Float,
        val averageEAR: Float,
        val mar: Float,
        val horizontalHeadDeviation: Float,
        val verticalHeadDeviation: Float,
        val yawDeg: Float,
        val pitchDeg: Float,
        val rollDeg: Float,
        val headPoseAvailable: Boolean
    )


    // Tính các đặc trưng từ landmarks và ma trận biến đổi khuôn mặt
    fun extract(
        landmarks: List<NormalizedLandmark>,
        imageWidth: Int,
        imageHeight: Int,
        transformMatrixColumnMajor: FloatArray? = null
    ): FaceFeatures? {

        if (landmarks.size < 468) {
            return null
        }


        // Tính EAR mắt phải
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


        // Tính EAR mắt trái
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


        val averageEAR =
            (leftEAR + rightEAR) / 2f


        // Tính MAR
        val mar =
            calculateMAR(
                landmarks = landmarks,
                imageWidth = imageWidth,
                imageHeight = imageHeight
            )


        // Giữ phép đo 2D làm baseline
        val headDeviation =
            calculateHeadDeviation(
                landmarks = landmarks,
                imageWidth = imageWidth,
                imageHeight = imageHeight
            )


        // Tính yaw, pitch và roll
        val headPose =
            calculateHeadPose(
                transformMatrixColumnMajor
            )


        return FaceFeatures(
            leftEAR = leftEAR,
            rightEAR = rightEAR,
            averageEAR = averageEAR,
            mar = mar,
            horizontalHeadDeviation =
                headDeviation.horizontal,
            verticalHeadDeviation =
                headDeviation.vertical,
            yawDeg =
                headPose.yawDeg,
            pitchDeg =
                headPose.pitchDeg,
            rollDeg =
                headPose.rollDeg,
            headPoseAvailable =
                headPose.available
        )
    }


    // Tính Eye Aspect Ratio
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


    // Tính Mouth Aspect Ratio
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


        return (
                verticalLeft +
                        verticalCenter +
                        verticalRight
                ) / (
                3f * mouthWidth
                )
    }


    // Tính độ lệch 2D của mũi so với khuôn mặt
    private fun calculateHeadDeviation(
        landmarks: List<NormalizedLandmark>,
        imageWidth: Int,
        imageHeight: Int
    ): HeadDeviation {

        val nose =
            point(
                landmarks[1],
                imageWidth,
                imageHeight
            )


        val leftFace =
            point(
                landmarks[234],
                imageWidth,
                imageHeight
            )


        val rightFace =
            point(
                landmarks[454],
                imageWidth,
                imageHeight
            )


        val forehead =
            point(
                landmarks[10],
                imageWidth,
                imageHeight
            )


        val chin =
            point(
                landmarks[152],
                imageWidth,
                imageHeight
            )


        val faceCenterX =
            (leftFace.x + rightFace.x) / 2f


        val faceCenterY =
            (forehead.y + chin.y) / 2f


        val faceWidth =
            distance(
                leftFace,
                rightFace
            )


        val faceHeight =
            distance(
                forehead,
                chin
            )


        if (
            faceWidth <= 0.0001f ||
            faceHeight <= 0.0001f
        ) {

            return HeadDeviation(
                horizontal = 0f,
                vertical = 0f
            )
        }


        val horizontalDeviation =
            (nose.x - faceCenterX) /
                    faceWidth


        val verticalDeviation =
            (nose.y - faceCenterY) /
                    faceHeight


        return HeadDeviation(
            horizontal = horizontalDeviation,
            vertical = verticalDeviation
        )
    }


    // Tính yaw, pitch và roll từ ma trận MediaPipe
    private fun calculateHeadPose(
        transformMatrixColumnMajor: FloatArray?
    ): HeadPose {

        if (
            transformMatrixColumnMajor == null ||
            transformMatrixColumnMajor.size != 16
        ) {

            return HeadPose(
                yawDeg = 0f,
                pitchDeg = 0f,
                rollDeg = 0f,
                available = false
            )
        }


        val matrix =
            transpose4x4(
                transformMatrixColumnMajor
            )


        val angles =
            eulerAnglesDegFromMatrix(
                matrix
            )


        return HeadPose(
            yawDeg = angles.first,
            pitchDeg = angles.second,
            rollDeg = angles.third,
            available = true
        )
    }


    // Chuyển ma trận MediaPipe từ column-major sang row-major
    private fun transpose4x4(
        matrix: FloatArray
    ): FloatArray {

        val result =
            FloatArray(16)


        for (row in 0 until 4) {

            for (column in 0 until 4) {

                result[row * 4 + column] =
                    matrix[column * 4 + row]
            }
        }


        return result
    }


    // Giải mã yaw, pitch và roll từ ma trận xoay
    private fun eulerAnglesDegFromMatrix(
        matrix: FloatArray
    ): Triple<Float, Float, Float> {

        val r00 = matrix[0]
        val r10 = matrix[4]
        val r11 = matrix[5]
        val r12 = matrix[6]
        val r20 = matrix[8]
        val r21 = matrix[9]
        val r22 = matrix[10]


        val sinYaw =
            (-r20)
                .toDouble()
                .coerceIn(
                    -1.0,
                    1.0
                )


        val yawRad =
            asin(
                sinYaw
            )


        val cosYaw =
            cos(
                yawRad
            )


        val pitchRad: Double

        val rollRad: Double


        if (cosYaw > 0.000001) {

            pitchRad =
                atan2(
                    r21.toDouble(),
                    r22.toDouble()
                )


            rollRad =
                atan2(
                    r10.toDouble(),
                    r00.toDouble()
                )

        } else {

            // Xử lý khi góc quay gần gimbal lock
            pitchRad =
                atan2(
                    (-r12).toDouble(),
                    r11.toDouble()
                )


            rollRad =
                0.0
        }


        val toDegrees =
            180.0 / Math.PI


        return Triple(
            (yawRad * toDegrees).toFloat(),
            (pitchRad * toDegrees).toFloat(),
            (rollRad * toDegrees).toFloat()
        )
    }


    // Chuyển landmark sang tọa độ pixel
    private fun point(
        landmark: NormalizedLandmark,
        imageWidth: Int,
        imageHeight: Int
    ): Point2D {

        return Point2D(
            x =
                landmark.x() *
                        imageWidth.toFloat(),

            y =
                landmark.y() *
                        imageHeight.toFloat()
        )
    }


    // Tính khoảng cách giữa hai điểm
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


    // Kết quả độ lệch đầu 2D
    private data class HeadDeviation(
        val horizontal: Float,
        val vertical: Float
    )


    // Kết quả head pose
    private data class HeadPose(
        val yawDeg: Float,
        val pitchDeg: Float,
        val rollDeg: Float,
        val available: Boolean
    )


    // Tọa độ 2D
    private data class Point2D(
        val x: Float,
        val y: Float
    )
}