import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.Call
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

data class ValidationResponse(
    val message: String
)

interface ApiService {
    @Multipart
    @POST("parkinson/upload_sensor_data/")
    suspend fun uploadSensorData(
        @Part("user_name") userName: RequestBody,
        @Part("study_name") studyName: RequestBody,
        @Part("application_type") applicationType: RequestBody,
        @Part("num_rows") numRows: RequestBody,
        @Part("start_timestamp") startTimestamp: RequestBody,
        @Part("end_timestamp") endTimestamp: RequestBody,
        @Part file: MultipartBody.Part
    ): Response<ResponseBody>

    @GET("/validate_user/{userID}")
    fun validateUser(
        @Path("userID") userID: String,
        @Query("previousUserId") previousUserId: String
    ): Call<ValidationResponse>
}
