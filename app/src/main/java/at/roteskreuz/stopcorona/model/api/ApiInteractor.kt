package at.roteskreuz.stopcorona.model.api

import at.roteskreuz.stopcorona.model.entities.configuration.ApiConfiguration
import at.roteskreuz.stopcorona.model.entities.infection.exposure_keys.ApiIndexOfDiagnosisKeysArchives
import at.roteskreuz.stopcorona.model.entities.infection.info.*
import at.roteskreuz.stopcorona.model.entities.infection.message.ApiInfectionMessages
import at.roteskreuz.stopcorona.model.entities.tan.ApiRequestTan
import at.roteskreuz.stopcorona.model.entities.tan.ApiRequestTanBody
import at.roteskreuz.stopcorona.model.repositories.DataPrivacyRepository
import at.roteskreuz.stopcorona.model.repositories.other.ContextInteractor
import at.roteskreuz.stopcorona.skeleton.core.model.exceptions.*
import at.roteskreuz.stopcorona.skeleton.core.model.helpers.AppDispatchers
import com.squareup.moshi.JsonDataException
import kotlinx.coroutines.withContext
import org.threeten.bp.ZonedDateTime
import retrofit2.HttpException
import timber.log.Timber
import java.io.*
import java.net.HttpURLConnection.*

/**
 * Interactor that communicates with API.
 */
interface ApiInteractor {

    /**
     * Get the current configuration.
     * @throws [ApiError]
     */
    suspend fun getConfiguration(): ApiConfiguration

    /**
     * Get infection messages.
     * Returns the last if [fromId] is null.
     * Otherwise it returns one page of 100 messages from [fromId] (not included).
     *
     * Messages are filtered by our supplied [addressPrefix]
     *
     * @throws [ApiError]
     */
    suspend fun getInfectionMessages(addressPrefix: String, fromId: Long? = null): ApiInfectionMessages

    /**
     * Request the server to send a TAN via text message
     * @throws [ApiError]
     *
     * @param mobileNumber The phonenumber to send the text message to
     * @return
     */
    suspend fun requestTan(mobileNumber: String): ApiRequestTan

    /**
     * Upload infection data about the user.
     */
    suspend fun uploadInfectionData(
        temporaryTracingKeyList: List<ApiTemporaryTracingKey>,
        packageName: String,
        diagnosisType: WarningType,
        verificationPayload: ApiVerificationPayload
    )

    /**
     * retrieve listing of exposure key archives
     */
    suspend fun getIndexOfDiagnosisKeysArchives(): ApiIndexOfDiagnosisKeysArchives

    /**
     * Save one file from the Content Delivery Network API to a local temp file.
     */
    suspend fun downloadContentDeliveryFileToTempFile(pathToArchive: String): File

    /**
     * Based on the users [WarningType], download the last 7 or 14 day batch of diagnosis key
     * archive(s).
     */
    suspend fun fetchBatchDiagnosisKeysBasedOnInfectionLevel(warningType: WarningType): List<File>

    /**
     * Download all available diagnosis key archive(s) for all available past days for individual
     * processing.
     */
    suspend fun fetchDailyBatchDiagnosisKeys(): ListOfDailyBatches
}

class ApiInteractorImpl(
    private val appDispatchers: AppDispatchers,
    private val apiDescription: ApiDescription,
    private val contextInteractor: ContextInteractor,
    private val tanApiDescription: TanApiDescription,
    private val contentDeliveryNetworkDescription: ContentDeliveryNetworkDescription,
    private val dataPrivacyRepository: DataPrivacyRepository
) : ApiInteractor,
    ExceptionMapperHelper {

    companion object {
        private const val ANDROID_OS = "android"
        private const val VERIFICATION_AUTHORITY_NAME = "RedCross"
        private val regions = arrayListOf("AT")
    }

    private val generalExceptionMapper: (HttpException) -> Exception? = {
        when (it.code()) {
            HTTP_FORBIDDEN -> ApiError.Critical.AuthorizationError
            HTTP_GONE -> ApiError.Critical.ForceUpdate
            else -> null
        }
    }

    /**
     * Map http errors to application domain.
     * @throws [ApiError]
     */
    private suspend fun <T> checkGeneralErrors(
        httpExceptionMapper: (HttpException) -> Exception? = generalExceptionMapper,
        criticalAction: suspend () -> T
    ): T {
        return try {
            criticalAction()
        } catch (e: Exception) {
            throw resolveException(e, httpExceptionMapper)
        }
    }

    override suspend fun getConfiguration(): ApiConfiguration {
        return withContext(appDispatchers.IO) {
            dataPrivacyRepository.assertDataPrivacyAccepted()
            checkGeneralErrors {
                apiDescription.configuration().configuration
            }
        }
    }

    override suspend fun getInfectionMessages(addressPrefix: String, fromId: Long?): ApiInfectionMessages {
        return withContext(appDispatchers.IO) {
            dataPrivacyRepository.assertDataPrivacyAccepted()
            checkGeneralErrors {
                apiDescription.infectionMessages(addressPrefix, fromId)
            }
        }
    }

    override suspend fun getIndexOfDiagnosisKeysArchives(): ApiIndexOfDiagnosisKeysArchives {
        return withContext(appDispatchers.IO) {
            dataPrivacyRepository.assertDataPrivacyAccepted()
            checkGeneralErrors {
                contentDeliveryNetworkDescription.indexOfDiagnosisKeysArchives()
            }
        }
    }

    override suspend fun downloadContentDeliveryFileToTempFile(pathToArchive: String): File {
        return withContext(appDispatchers.IO) {
            return@withContext checkGeneralErrors {
                val response = contentDeliveryNetworkDescription.downloadExposureKeyArchive(pathToArchive).execute()
                if (response.isSuccessful) {
                    val cacheDir = contextInteractor.applicationContext.getCacheDir()
                    val outputFile: File = File(cacheDir, pathToArchive.replace("/", "-"))

                    outputFile.deleteOnExit()

                    response.body()?.byteStream()?.saveToFile(outputFile)
                    return@checkGeneralErrors outputFile
                } else {
                    response.message()
                    throw IOException("it did not work code:${response} ")
                }
            }
        }
    }

    override suspend fun fetchBatchDiagnosisKeysBasedOnInfectionLevel(warningType: WarningType): List<File> {
        val indexOfArchives = getIndexOfDiagnosisKeysArchives()

        when (warningType) {
            WarningType.YELLOW, WarningType.RED -> {
                return indexOfArchives.full14DaysBatch.batchFilePaths.map {
                    Timber.d("Downloading the full 14 days batch, ther could revocations" +
                        "in the last 14 days even")
                    downloadContentDeliveryFileToTempFile(it)
                }
            }
            WarningType.REVOKE -> {
                return indexOfArchives.full07DaysBatch.batchFilePaths.map {
                    Timber.d("Downloading the full 7 days batch because there can´t" +
                        "be changes longer than 7 days ago as we were not exposed.")
                    downloadContentDeliveryFileToTempFile(it)
                }
            }
        }
    }

    override suspend fun fetchDailyBatchDiagnosisKeys(): ListOfDailyBatches {
        val indexOfArchives = getIndexOfDiagnosisKeysArchives()

        //we assume the list of dailyBatches is sorted on the server!!!
        val dailyArchives = ListOfDailyBatches()
        indexOfArchives.dailyBatches.forEachIndexed { index, dayBatch ->
            val downloadedFilesOfThisDay = dayBatch.batchFilePaths.map { filepathForOneDay ->
                downloadContentDeliveryFileToTempFile(filepathForOneDay)
            }

            val dayArchive = ArchivesOfOneDay(
                archiveFilePaths = downloadedFilesOfThisDay,
                dayTimestampOfDay = dayBatch.intervalToEpochSeconds,
                indexFromServer = index
            )
            dailyArchives.diagnosisArchiveFilesOfTheDay.add(dayArchive)
        }
        return dailyArchives
    }

    private fun InputStream.saveToFile(file: File) = use { input ->
        file.outputStream().use { output ->
            input.copyTo(output)
        }
    }

    override suspend fun requestTan(mobileNumber: String): ApiRequestTan {
        return withContext(appDispatchers.IO) {
            dataPrivacyRepository.assertDataPrivacyAccepted()
            checkGeneralErrors({ httpException ->
                when (httpException.code()) {
                    HTTP_UNAUTHORIZED -> SicknessCertificateUploadException.PhoneNumberInvalidException
                    HTTP_INTERNAL_ERROR -> SicknessCertificateUploadException.SMSGatewayException
                    else -> generalExceptionMapper(httpException)
                }
            },
                {
                    tanApiDescription.requestTan(ApiRequestTanBody(mobileNumber))
                })
        }
    }

    override suspend fun uploadInfectionData(
        temporaryTracingKeyList: List<ApiTemporaryTracingKey>,
        packageName: String,
        diagnosisType: WarningType,
        verificationPayload: ApiVerificationPayload
    ) {
        withContext(appDispatchers.IO) {
            dataPrivacyRepository.assertDataPrivacyAccepted()
            checkGeneralErrors(
                { httpException ->
                    when (httpException.code()) {
                        HTTP_FORBIDDEN -> SicknessCertificateUploadException.TanInvalidException
                        else -> generalExceptionMapper(httpException)
                    }
                },
                {
                    apiDescription.publish(
                        ApiInfectionDataRequest(
                            temporaryTracingKeyList,
                            regions,
                            packageName,
                            ANDROID_OS,
                            diagnosisType,
                            VERIFICATION_AUTHORITY_NAME,
                            verificationPayload
                        )
                    )
                }
            )
        }
    }
}

/**
 * Exception in application domain.
 *
 * Can be also [GeneralServerException], [NoInternetConnectionException], [UnexpectedError].
 */
sealed class ApiError : Exception() {

    sealed class Critical : ApiError() {
        /**
         * Correct authorization required in http header.
         */
        object AuthorizationError : Critical()

        /**
         * API version discontinued. Client must be updated.
         */
        object ForceUpdate : Critical()

        /**
         * API call when data privacy is not accepted yet, which is violation of GDPR.
         */
        object DataPrivacyNotAcceptedYet : Critical()
    }
}

/**
 * Exceptions triggered when uploading infection info.
 */
sealed class SicknessCertificateUploadException : Exception() {

    /**
     * Triggered when the TAN used to upload the infection is invalid.
     */
    object TanInvalidException : SicknessCertificateUploadException()

    /**
     * Triggered when the birthday does not have the expected format (dd.MM.YYYY).
     */
    object BirthdayInvalidException : SicknessCertificateUploadException()

    /**
     * Triggered in case the phone number used for requesting a TAN is invalid.
     */
    object PhoneNumberInvalidException : SicknessCertificateUploadException()

    /**
     * Triggered in case of an SMS Gateway error.
     */
    object SMSGatewayException : SicknessCertificateUploadException()
}

/**
 * Collection of downloaded diagnosis archives
 */
data class ListOfDailyBatches(
    val diagnosisArchiveFilesOfTheDay: MutableList<ArchivesOfOneDay> = ArrayList()
)

/**
 * One day worth of diagnosis key files
 */
data class ArchivesOfOneDay(
    /**
     * path to the diagnosis key files downloaded from the backend as local temp files
     */
    val archiveFilePaths: List<File>,
    /**
     * unix timestamp of the day
     */
    val dayTimestampOfDay: Long,
    /**
     * The original index as ordered by the backend
     */
    val indexFromServer: Int
)