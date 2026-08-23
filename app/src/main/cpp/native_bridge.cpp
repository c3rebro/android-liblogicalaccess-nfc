#include <jni.h>

#include <algorithm>
#include <cctype>
#include <cstdint>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <thread>
#include <vector>

#include <boost/property_tree/ptree.hpp>

#include <logicalaccess/readerproviders/datatransport.hpp>
#include <logicalaccess/cards/chip.hpp>
#include <logicalaccess/plugins/cards/desfire/desfireev1chip.hpp>
#include <logicalaccess/plugins/cards/desfire/desfirekey.hpp>
#include <logicalaccess/plugins/cards/desfire/desfirecommands.hpp>
#include <logicalaccess/plugins/cards/iso7816/readercardadapters/iso7816readercardadapter.hpp>
#include <logicalaccess/plugins/readers/iso7816/commands/desfireev1iso7816commands.hpp>
#include <logicalaccess/plugins/readers/iso7816/commands/desfireiso7816resultchecker.hpp>

namespace {

constexpr std::uint8_t STATUS_OK = 0;
constexpr std::uint8_t STATUS_AUTH_FAILURE = 1;
constexpr std::uint8_t STATUS_PERMISSION_DENIED = 2;
constexpr std::uint8_t STATUS_PROTOCOL_CONSTRAINT = 3;
constexpr std::uint8_t STATUS_TRANSPORT_ERROR = 4;
constexpr std::uint8_t STATUS_UNKNOWN = 5;

constexpr int OP_GET_VERSION = 1;
constexpr int OP_GET_FREE_MEMORY = 2;
constexpr int OP_LIST_APPLICATIONS = 3;
constexpr int OP_AUTHENTICATE = 4;
constexpr int OP_READ_APPLICATION_SETTINGS = 5;
constexpr int OP_LIST_FILES = 6;
constexpr int OP_READ_FILE_SETTINGS = 7;

jobject g_transport = nullptr;
std::mutex g_transport_mutex;

std::string lowercase(std::string text) {
    std::transform(text.begin(), text.end(), text.begin(), [](unsigned char c) {
        return static_cast<char>(std::tolower(c));
    });
    return text;
}

std::uint8_t classify_exception(const std::exception& error) {
    const auto text = lowercase(error.what() ? std::string(error.what()) : std::string());

    if (text.find("auth") != std::string::npos ||
        text.find("status does not allow") != std::string::npos ||
        text.find("integrity error") != std::string::npos) {
        return STATUS_AUTH_FAILURE;
    }
    if (text.find("permission") != std::string::npos ||
        text.find("security status") != std::string::npos ||
        text.find("access denied") != std::string::npos) {
        return STATUS_PERMISSION_DENIED;
    }
    if (text.find("same number already exists") != std::string::npos ||
        text.find("parameter") != std::string::npos ||
        text.find("protocol") != std::string::npos ||
        text.find("wrong class") != std::string::npos ||
        text.find("wrong instruction") != std::string::npos) {
        return STATUS_PROTOCOL_CONSTRAINT;
    }
    if (text.find("timeout") != std::string::npos ||
        text.find("transport") != std::string::npos ||
        text.find("transceive") != std::string::npos ||
        text.find("tag was lost") != std::string::npos ||
        text.find("not connected") != std::string::npos) {
        return STATUS_TRANSPORT_ERROR;
    }
    return STATUS_UNKNOWN;
}

jbyteArray to_java_bytes(JNIEnv* env, const std::vector<std::uint8_t>& bytes) {
    auto array = env->NewByteArray(static_cast<jsize>(bytes.size()));
    if (!array) {
        throw std::runtime_error("Unable to allocate Java byte array.");
    }
    if (!bytes.empty()) {
        env->SetByteArrayRegion(
            array,
            0,
            static_cast<jsize>(bytes.size()),
            reinterpret_cast<const jbyte*>(bytes.data()));
    }
    return array;
}

std::vector<std::uint8_t> ok_packet(std::vector<std::uint8_t> payload = {}) {
    payload.insert(payload.begin(), STATUS_OK);
    return payload;
}

std::vector<std::uint8_t> error_packet(std::uint8_t status, const std::string& message) {
    std::vector<std::uint8_t> packet;
    packet.reserve(1 + message.size());
    packet.push_back(status);
    packet.insert(packet.end(), message.begin(), message.end());
    return packet;
}

void append_i32_le(std::vector<std::uint8_t>& out, std::int32_t value) {
    const auto unsigned_value = static_cast<std::uint32_t>(value);
    out.push_back(static_cast<std::uint8_t>(unsigned_value & 0xFF));
    out.push_back(static_cast<std::uint8_t>((unsigned_value >> 8) & 0xFF));
    out.push_back(static_cast<std::uint8_t>((unsigned_value >> 16) & 0xFF));
    out.push_back(static_cast<std::uint8_t>((unsigned_value >> 24) & 0xFF));
}

void clear_global_transport(JNIEnv* env) {
    std::lock_guard<std::mutex> lock(g_transport_mutex);
    if (g_transport != nullptr) {
        env->DeleteGlobalRef(g_transport);
        g_transport = nullptr;
    }
}

class AndroidIsoDepDataTransport final : public logicalaccess::DataTransport {
public:
    AndroidIsoDepDataTransport(JNIEnv* env, jobject transport)
        : env_(env), owner_thread_(std::this_thread::get_id()) {
        if (!transport) {
            throw std::invalid_argument("Android IsoDep transport is null.");
        }
        transport_ = env_->NewGlobalRef(transport);
        if (!transport_) {
            throw std::runtime_error("Unable to retain Android IsoDep transport.");
        }

        jclass cls = env_->GetObjectClass(transport_);
        if (!cls) {
            throw std::runtime_error("Unable to resolve AndroidIsoDepTransport class.");
        }
        transceive_ = env_->GetMethodID(cls, "transceive", "([B)[B");
        is_connected_ = env_->GetMethodID(cls, "isConnected", "()Z");
        env_->DeleteLocalRef(cls);

        if (!transceive_ || !is_connected_) {
            throw std::runtime_error("AndroidIsoDepTransport JNI contract is incomplete.");
        }
    }

    ~AndroidIsoDepDataTransport() override {
        if (transport_ && std::this_thread::get_id() == owner_thread_) {
            env_->DeleteGlobalRef(transport_);
        }
    }

    std::string getTransportType() const override { return "AndroidIsoDep"; }
    std::string getName() const override { return "Android IsoDep"; }

    bool connect() override { return isConnected(); }
    void disconnect() override {}

    bool isConnected() override {
        enforce_thread();
        const auto value = env_->CallBooleanMethod(transport_, is_connected_);
        check_java_exception("IsoDep.isConnected");
        return value == JNI_TRUE;
    }

    void serialize(boost::property_tree::ptree&) override {}
    void unSerialize(boost::property_tree::ptree&) override {}
    std::string getDefaultXmlNodeName() const override { return "AndroidIsoDepDataTransport"; }

protected:
    void send(const logicalaccess::ByteVector& data) override {
        enforce_thread();

        jbyteArray request = env_->NewByteArray(static_cast<jsize>(data.size()));
        if (!request) {
            throw std::runtime_error("Unable to allocate IsoDep request buffer.");
        }
        if (!data.empty()) {
            env_->SetByteArrayRegion(
                request,
                0,
                static_cast<jsize>(data.size()),
                reinterpret_cast<const jbyte*>(data.data()));
        }

        auto response = static_cast<jbyteArray>(env_->CallObjectMethod(transport_, transceive_, request));
        env_->DeleteLocalRef(request);
        check_java_exception("IsoDep.transceive");

        if (!response) {
            throw std::runtime_error("IsoDep.transceive returned null.");
        }

        const auto length = env_->GetArrayLength(response);
        pending_.resize(static_cast<std::size_t>(length));
        if (length > 0) {
            env_->GetByteArrayRegion(
                response,
                0,
                length,
                reinterpret_cast<jbyte*>(pending_.data()));
        }
        env_->DeleteLocalRef(response);
    }

    logicalaccess::ByteVector receive(long int) override {
        enforce_thread();
        auto result = std::move(pending_);
        pending_.clear();
        return result;
    }

private:
    void enforce_thread() const {
        if (std::this_thread::get_id() != owner_thread_) {
            throw std::runtime_error("Android IsoDep DataTransport used from a different JNI thread.");
        }
    }

    void check_java_exception(const char* operation) {
        if (env_->ExceptionCheck()) {
            env_->ExceptionClear();
            throw std::runtime_error(std::string(operation) + " raised a Java NFC exception.");
        }
    }

    JNIEnv* env_;
    jobject transport_ = nullptr;
    jmethodID transceive_ = nullptr;
    jmethodID is_connected_ = nullptr;
    std::thread::id owner_thread_;
    logicalaccess::ByteVector pending_;
};

struct NativeDesfireSession {
    NativeDesfireSession(JNIEnv* env, jobject java_transport, const logicalaccess::ByteVector& uid)
        : owner_thread(std::this_thread::get_id()) {
        transport = std::make_shared<AndroidIsoDepDataTransport>(env, java_transport);
        if (!transport->isConnected()) {
            throw std::runtime_error("IsoDep is not connected when creating DESFire session.");
        }

        adapter = std::make_shared<logicalaccess::ISO7816ReaderCardAdapter>();
        adapter->setDataTransport(transport);
        adapter->setResultChecker(std::make_shared<logicalaccess::DESFireISO7816ResultChecker>());

        chip = std::make_shared<logicalaccess::DESFireEV1Chip>();
        chip->setChipIdentifier(uid);

        commands = std::make_shared<logicalaccess::DESFireEV1ISO7816Commands>();
        commands->setReaderCardAdapter(adapter);
        commands->setChip(chip);
        chip->setCommands(commands);
    }

    void enforce_thread() const {
        if (std::this_thread::get_id() != owner_thread) {
            throw std::runtime_error("DESFire native session used from a different JNI thread.");
        }
    }

    std::shared_ptr<AndroidIsoDepDataTransport> transport;
    std::shared_ptr<logicalaccess::ISO7816ReaderCardAdapter> adapter;
    std::shared_ptr<logicalaccess::DESFireEV1Chip> chip;
    std::shared_ptr<logicalaccess::DESFireEV1ISO7816Commands> commands;
    std::thread::id owner_thread;
};

logicalaccess::DESFireKeyType native_key_type(jint value) {
    switch (value) {
        case 0: return logicalaccess::DF_KEY_DES;
        case 1: return logicalaccess::DF_KEY_3K3DES;
        case 2: return logicalaccess::DF_KEY_AES;
        default: throw std::invalid_argument("Unknown DESFire key type.");
    }
}

std::shared_ptr<logicalaccess::DESFireKey> make_key(
    JNIEnv* env,
    jint key_type,
    jint key_no,
    jbyteArray key_data) {

    if (!key_data) {
        throw std::invalid_argument("Authentication requested without a DESFire key.");
    }
    if (key_no < 0 || key_no > 15) {
        throw std::invalid_argument("DESFire key number must be between 0 and 15.");
    }

    const auto type = native_key_type(key_type);
    const auto length = env->GetArrayLength(key_data);
    const auto expected = type == logicalaccess::DF_KEY_3K3DES ? 24 : 16;
    if (length != expected) {
        throw std::invalid_argument("DESFire key has an invalid length for its key type.");
    }

    logicalaccess::ByteVector bytes(static_cast<std::size_t>(length));
    env->GetByteArrayRegion(key_data, 0, length, reinterpret_cast<jbyte*>(bytes.data()));

    auto key = std::make_shared<logicalaccess::DESFireKey>();
    key->setKeyType(type);
    key->setData(bytes);
    return key;
}

std::uint8_t native_comm_mode(unsigned char value) {
    switch (value & 0x03) {
        case 0:
        case 2: return 0; // PLAIN
        case 1: return 1; // MACED
        case 3: return 2; // ENCIPHERED
        default: return 0;
    }
}

std::uint8_t native_key_type_code(logicalaccess::DESFireKeyType type) {
    switch (type) {
        case logicalaccess::DF_KEY_DES: return 0;
        case logicalaccess::DF_KEY_3K3DES: return 1;
        case logicalaccess::DF_KEY_AES: return 2;
        default: return 0;
    }
}

std::int32_t file_length(const logicalaccess::DESFireCommands::FileSetting& settings) {
    // Match RFIDGear's interpretation for data files, while preserving useful
    // record-size metadata where the union provides it directly.
    std::uint32_t value = 0;
    switch (settings.fileType) {
        case 0: // standard data
        case 1: // backup data
            value = static_cast<std::uint32_t>(settings.type.dataFile.fileSize[0]) |
                    (static_cast<std::uint32_t>(settings.type.dataFile.fileSize[1]) << 8) |
                    (static_cast<std::uint32_t>(settings.type.dataFile.fileSize[2]) << 16);
            return static_cast<std::int32_t>(value);
        case 3: // linear record
        case 4: // cyclic record
            value = static_cast<std::uint32_t>(settings.type.recordFile.recordSize[0]) |
                    (static_cast<std::uint32_t>(settings.type.recordFile.recordSize[1]) << 8) |
                    (static_cast<std::uint32_t>(settings.type.recordFile.recordSize[2]) << 16);
            return static_cast<std::int32_t>(value);
        default:
            return -1;
    }
}

std::vector<std::uint8_t> execute_read_only(
    JNIEnv* env,
    NativeDesfireSession& session,
    jint operation,
    jint app_id,
    jint file_no,
    jint key_type,
    jint key_no,
    jbyteArray key_data,
    jboolean authenticate) {

    session.enforce_thread();
    auto& commands = *session.commands;
    const bool should_authenticate = authenticate == JNI_TRUE;

    auto authenticate_if_requested = [&](unsigned int aid) {
        commands.selectApplication(aid);
        if (should_authenticate) {
            auto key = make_key(env, key_type, key_no, key_data);
            commands.authenticate(static_cast<unsigned char>(key_no), key);
        }
    };

    switch (operation) {
        case OP_GET_VERSION: {
            const auto version = commands.getVersion();
            return ok_packet({
                version.hardwareVendor,
                version.hardwareType,
                version.hardwareSubType,
                version.hardwareMjVersion,
                version.hardwareMnVersion,
                version.hardwareStorageSize,
                version.hardwareProtocol,
                version.softwareVendor,
                version.softwareType,
                version.softwareSubType,
                version.softwareMjVersion,
                version.softwareMnVersion,
                version.softwareStorageSize,
                version.softwareProtocol,
                version.cwProd,
                version.yearProd
            });
        }

        case OP_GET_FREE_MEMORY: {
            const auto bytes = static_cast<std::int32_t>(commands.getFreeMem());
            std::vector<std::uint8_t> payload;
            append_i32_le(payload, bytes);
            return ok_packet(std::move(payload));
        }

        case OP_LIST_APPLICATIONS: {
            authenticate_if_requested(0);
            const auto aids = commands.getApplicationIDs();
            std::vector<std::uint8_t> payload;
            payload.push_back(should_authenticate ? 1 : 0);
            append_i32_le(payload, static_cast<std::int32_t>(aids.size()));
            for (const auto aid : aids) {
                append_i32_le(payload, static_cast<std::int32_t>(aid));
            }
            return ok_packet(std::move(payload));
        }

        case OP_AUTHENTICATE: {
            commands.selectApplication(static_cast<unsigned int>(app_id));
            auto key = make_key(env, key_type, key_no, key_data);
            commands.authenticate(static_cast<unsigned char>(key_no), key);
            return ok_packet();
        }

        case OP_READ_APPLICATION_SETTINGS: {
            authenticate_if_requested(static_cast<unsigned int>(app_id));

            logicalaccess::DESFireKeySettings settings{};
            unsigned char max_keys = 0;
            logicalaccess::DESFireKeyType type = logicalaccess::DF_KEY_DES;
            commands.getKeySettings(settings, max_keys, type);

            return ok_packet({
                static_cast<std::uint8_t>(settings),
                static_cast<std::uint8_t>(max_keys & 0x0F),
                native_key_type_code(type),
                static_cast<std::uint8_t>(should_authenticate ? 1 : 0)
            });
        }

        case OP_LIST_FILES: {
            authenticate_if_requested(static_cast<unsigned int>(app_id));
            const auto file_ids = commands.getFileIDs();
            if (file_ids.size() > 255) {
                throw std::runtime_error("DESFire returned more than 255 file IDs.");
            }

            std::vector<std::uint8_t> payload;
            payload.reserve(2 + file_ids.size());
            payload.push_back(should_authenticate ? 1 : 0);
            payload.push_back(static_cast<std::uint8_t>(file_ids.size()));
            payload.insert(payload.end(), file_ids.begin(), file_ids.end());
            return ok_packet(std::move(payload));
        }

        case OP_READ_FILE_SETTINGS: {
            if (file_no < 0 || file_no > 255) {
                throw std::invalid_argument("DESFire file number is outside byte range.");
            }

            authenticate_if_requested(static_cast<unsigned int>(app_id));
            const auto settings = commands.getFileSettings(static_cast<unsigned char>(file_no));

            // DESFire wire access-rights layout:
            // wire[0] high=ReadWrite, low=Change; wire[1] high=Read, low=Write.
            const auto read = static_cast<std::uint8_t>((settings.accessRights[1] >> 4) & 0x0F);
            const auto write = static_cast<std::uint8_t>(settings.accessRights[1] & 0x0F);
            const auto read_write = static_cast<std::uint8_t>((settings.accessRights[0] >> 4) & 0x0F);
            const auto change = static_cast<std::uint8_t>(settings.accessRights[0] & 0x0F);

            std::vector<std::uint8_t> payload = {
                static_cast<std::uint8_t>(file_no),
                static_cast<std::uint8_t>(settings.fileType),
                native_comm_mode(settings.comSett),
                read,
                write,
                read_write,
                change,
                static_cast<std::uint8_t>(should_authenticate ? 1 : 0)
            };
            append_i32_le(payload, file_length(settings));
            return ok_packet(std::move(payload));
        }

        default:
            throw std::invalid_argument("Unknown or non-read-only DESFire operation requested.");
    }
}

} // namespace

extern "C"
JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM*, void*) {
    return JNI_VERSION_1_6;
}

extern "C"
JNIEXPORT jstring JNICALL
Java_de_shansen_liblogicalaccessnfc_NativeBridge_version(JNIEnv* env, jobject) {
    return env->NewStringUTF("JNI bridge active; liblogicalaccess 3.7.0 DESFire Quick Check enabled");
}

extern "C"
JNIEXPORT void JNICALL
Java_de_shansen_liblogicalaccessnfc_NativeBridge_attachTransport(
    JNIEnv* env,
    jobject,
    jobject transport) {
    clear_global_transport(env);
    std::lock_guard<std::mutex> lock(g_transport_mutex);
    g_transport = env->NewGlobalRef(transport);
}

extern "C"
JNIEXPORT void JNICALL
Java_de_shansen_liblogicalaccessnfc_NativeBridge_detachTransport(JNIEnv* env, jobject) {
    clear_global_transport(env);
}

extern "C"
JNIEXPORT jlong JNICALL
Java_de_shansen_liblogicalaccessnfc_NativeBridge_beginDesfireSession(
    JNIEnv* env,
    jobject,
    jbyteArray uid_array) {

    try {
        jobject transport = nullptr;
        {
            std::lock_guard<std::mutex> lock(g_transport_mutex);
            transport = g_transport;
        }
        if (!transport) {
            throw std::runtime_error("No Android IsoDep transport is attached.");
        }

        logicalaccess::ByteVector uid;
        if (uid_array) {
            const auto length = env->GetArrayLength(uid_array);
            uid.resize(static_cast<std::size_t>(length));
            if (length > 0) {
                env->GetByteArrayRegion(uid_array, 0, length, reinterpret_cast<jbyte*>(uid.data()));
            }
        }

        auto session = std::make_unique<NativeDesfireSession>(env, transport, uid);
        return reinterpret_cast<jlong>(session.release());
    } catch (const std::exception& error) {
        jclass exception = env->FindClass("java/lang/IllegalStateException");
        if (exception) {
            env->ThrowNew(exception, error.what());
        }
        return 0;
    }
}

extern "C"
JNIEXPORT void JNICALL
Java_de_shansen_liblogicalaccessnfc_NativeBridge_endDesfireSession(
    JNIEnv*,
    jobject,
    jlong handle) {
    auto* session = reinterpret_cast<NativeDesfireSession*>(handle);
    if (session) {
        delete session;
    }
}

extern "C"
JNIEXPORT jbyteArray JNICALL
Java_de_shansen_liblogicalaccessnfc_NativeBridge_desfireExecute(
    JNIEnv* env,
    jobject,
    jlong handle,
    jint operation,
    jint app_id,
    jint file_no,
    jint key_type,
    jint key_no,
    jbyteArray key_data,
    jboolean authenticate) {

    std::vector<std::uint8_t> packet;
    try {
        auto* session = reinterpret_cast<NativeDesfireSession*>(handle);
        if (!session) {
            packet = error_packet(STATUS_TRANSPORT_ERROR, "DESFire native session is null.");
        } else {
            packet = execute_read_only(
                env,
                *session,
                operation,
                app_id,
                file_no,
                key_type,
                key_no,
                key_data,
                authenticate);
        }
    } catch (const std::invalid_argument& error) {
        packet = error_packet(STATUS_PROTOCOL_CONSTRAINT, error.what());
    } catch (const std::exception& error) {
        packet = error_packet(classify_exception(error), error.what());
    } catch (...) {
        packet = error_packet(STATUS_UNKNOWN, "Unknown native DESFire exception.");
    }

    try {
        return to_java_bytes(env, packet);
    } catch (const std::exception& error) {
        jclass exception = env->FindClass("java/lang/IllegalStateException");
        if (exception) {
            env->ThrowNew(exception, error.what());
        }
        return nullptr;
    }
}
