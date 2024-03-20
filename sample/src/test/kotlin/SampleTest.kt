import foo.*
import io.javalin.Javalin
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.io.path.writeBytes

private val cat = Pet(1, "Cat", "orange")
private val dog = Pet(2, "Dog", "black")
private val customPet = CustomPet(
    listOf(3, 4),
    listOf("one", "two"),
)

class SampleTest {

    private val petClinicClient = SampleClient("http://localhost:8080", clientConfig = {
        followRedirects(false)
    })

    companion object {

        private val javalin: Javalin = Javalin.create()
        private val petServer = PetServer()
        private val petController = SampleJavalinController(petServer)

        @JvmStatic
        @BeforeAll
        fun start() {
            petController.bind(javalin)
            javalin.start(8080)
        }
    }

    @BeforeEach
    fun cleanup() {
        petServer.reInit()
    }

    @Test
    fun shouldListPets() {
        val petsResponse = petClinicClient.listPets(10)

        Assertions.assertEquals(
            Pets(listOf(cat, dog)),
            (petsResponse as ListPetsResponse.Pets).pets
        )
    }

    @Test
    fun shouldReceiveError() {
        val petsResponse = petClinicClient.listPets(-1)

        Assertions.assertEquals(Error(400, "Limit <= 0"), (petsResponse as ListPetsResponse.Error).error)
    }

    @Test
    fun shouldCreatePetFormData() {
        // given
        val pet = Pet(5, "Tiger", "animal")

        // when
        val createResponse = petClinicClient.createPet(CreatePetRequest.Form(pet))
        val showResponse = petClinicClient.showPetById(5.toString(), "1.2") as ShowPetByIdResponse.Pet

        Assertions.assertInstanceOf(CreatePetResponse.Created::class.java, createResponse)

        // then
        Assertions.assertEquals(pet, showResponse.pet)
    }

    @Test
    fun shouldCreatePetFormJson() {
        // given
        val pet = Pet(5, "Tiger", "animal")

        // when
        val createResponse = petClinicClient.createPet(CreatePetRequest.Json(pet))
        val showResponse = petClinicClient.showPetById(5.toString(), "1.2") as ShowPetByIdResponse.Pet

        Assertions.assertInstanceOf(CreatePetResponse.Created::class.java, createResponse)

        // then
        Assertions.assertEquals(pet, showResponse.pet)
    }

    @Test
    fun shouldProcessRedirectHeader() {
        val response = petClinicClient.redirectUser() as RedirectUserResponse.Redirect

        Assertions.assertEquals(
            response.redirectUserResponseRedirectHeaders,
            RedirectUserResponseRedirectHeaders("https://google.com")
        )
    }

    @Test
    fun shouldSubmitCustomPet() {
        val res = petClinicClient.customPet(
            CustomPetRequest.Json(
                customPet
            )
        )
        Assertions.assertEquals(res, CustomPetResponse.Ok)
    }

    @Test
    fun shouldGetPetAvatar() {
        val avatar = (petClinicClient.getAvatarByPetId("some-pet") as GetAvatarByPetIdResponse.File)
            .file

        val bytes = avatar.readBytes()
        Assertions.assertArrayEquals(byteArrayOf(1, 2, 3), bytes)
        avatar.delete()
    }

    @Test
    fun shouldUpdatePetAvatar() {
        val tempPath = Files.createTempFile("tmp-", ".tmp")
        val photoContent = byteArrayOf(5, 3, 2, 3)

        tempPath.writeBytes(photoContent)
        tempPath.toFile().deleteOnExit()
        val fileUpload = FileUpload("avatar.jpg", tempPath.toFile(), "image/jpeg")
        val res = petClinicClient.updateAvatarById(
            "some-pet", UpdateAvatarByIdRequest.MultipartForm(AvatarUpload(fileUpload))
        )

        Assertions.assertInstanceOf(UpdateAvatarByIdResponse.Ok::class.java, res)
        Assertions.assertArrayEquals(petServer.lastUpdatedPetAvatar, photoContent)
    }
}

class PetServer : SampleSpec {

    private lateinit var pets: MutableMap<Long, Pet>
    var lastUpdatedPetAvatar: ByteArray? = null

    init {
        reInit()
    }

    override fun redirectUser(): RedirectUserResponse {
        return RedirectUserResponse.Redirect(RedirectUserResponseRedirectHeaders("https://google.com"))
    }

    override fun customPet(requestBody: CustomPetRequest): CustomPetResponse {
        require((requestBody as CustomPetRequest.Json).customPet == customPet)
        return CustomPetResponse.Ok
    }

    override fun listPets(limit: Int?): ListPetsResponse {
        return if (limit != null && limit > 0) {
            ListPetsResponse.Pets(Pets(pets.values.toList().take(limit)), ListPetsResponsePetsHeaders(null))
        } else {
            ListPetsResponse.Error(Error(400, "Limit <= 0"))
        }
    }

    override fun createPet(requestBody: CreatePetRequest): CreatePetResponse {
        val pet = when (requestBody) {
            is CreatePetRequest.Form -> requestBody.pet
            is CreatePetRequest.Json -> requestBody.pet
            is CreatePetRequest.Xml -> requestBody.pet
        }
        pets[pet.id] = pet
        return CreatePetResponse.Created
    }

    override fun showPetById(petId: String, `x-version`: String): ShowPetByIdResponse {
        require(`x-version` == "1.2")
        val pet = pets[petId.toLong()]
        return if (pet != null) {
            ShowPetByIdResponse.Pet(pet)
        } else {
            ShowPetByIdResponse.Error(Error(404, "Not found"))
        }
    }

    override fun getAvatarByPetId(petId: String): GetAvatarByPetIdResponse {
        val avatar = Files.createTempFile("tmp-", ".tmp").toFile()
        avatar.deleteOnExit()
        avatar.writeBytes(byteArrayOf(1, 2, 3))
        return GetAvatarByPetIdResponse.File(avatar)
    }

    override fun updateAvatarById(petId: String, requestBody: UpdateAvatarByIdRequest): UpdateAvatarByIdResponse {
        val filePhoto = (requestBody as UpdateAvatarByIdRequest.MultipartForm).avatarUpload.photo.file
        lastUpdatedPetAvatar = filePhoto.readBytes()
        return UpdateAvatarByIdResponse.Ok
    }

    fun reInit() {
        pets = mutableListOf(cat, dog).associateBy { it.id }.toMutableMap()
    }
}