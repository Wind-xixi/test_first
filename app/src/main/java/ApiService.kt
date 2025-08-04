package com.example.myapppy

import com.google.gson.annotations.SerializedName
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.POST

// --- 数据类定义 ---

// 用于教师评价API的响应
data class TeacherApiResponse(
    @SerializedName("grade" ) val grade: String,
    @SerializedName("summary") val summary: String
)

// 用于发送请求体
data class EvaluationRequestBody(
    val text: String
)

// --- Retrofit接口定义 (只保留教师评价) ---
interface ApiService {
    @POST("evaluate_teacher")
    suspend fun evaluateTeacher(@Body requestBody: EvaluationRequestBody): TeacherApiResponse
}

// --- Retrofit客户端单例对象 ---
object ApiClient {
    private const val BASE_URL = "https://flask-teachers-evaluate.onrender.com/"

    val instance: ApiService by lazy {
        Retrofit.Builder( )
            .baseUrl(BASE_URL)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ApiService::class.java)
    }
}
