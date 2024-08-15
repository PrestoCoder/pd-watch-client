import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import ApiService

object RetrofitClient {
//    private const val BASE_URL = "http://localhost:9000/"
    private const val BASE_URL = "http://10.0.2.2:9000/"
//    private const val BASE_URL = "http://mayo.abdullah-mamun.com:9000/"
//    private const val BASE_URL = "http://44.203.96.137:9000/"


    val instance: ApiService by lazy {
        val retrofit = Retrofit.Builder()
            .baseUrl(BASE_URL)
            .addConverterFactory(GsonConverterFactory.create())
            .build()

        retrofit.create(ApiService::class.java)
    }
}
