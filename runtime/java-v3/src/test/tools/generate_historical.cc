#include <algorithm>
#include <array>
#include <bit>
#include <cmath>
#include <cstdint>
#include <filesystem>
#include <fstream>
#include <limits>
#include <span>
#include <string>
#include <string_view>
#include <vector>
using Byte=std::uint8_t;
constexpr std::string_view kModelHash = "000102030405060708090a0b0c0d0e0f";
constexpr std::size_t kMaxPayloadCount = 32'766;

struct SoundFixture {
    std::string_view name;
    std::span<const Byte> bytes;
};

class FixtureWriter final {
   public:
    void FixedU32(std::uint32_t value) {
        for (int index = 0; index < 4; ++index) {
            bytes_.push_back(static_cast<Byte>(value >> (index * 8)));
        }
    }

    void VarU32(std::uint32_t value) {
        do {
            auto byte = static_cast<Byte>(value & 0x7FU);
            value >>= 7U;
            bytes_.push_back(value == 0 ? byte
                                        : static_cast<Byte>(byte | 0x80U));
        } while (value != 0);
    }

    void VarU64(std::uint64_t value) {
        do {
            auto byte = static_cast<Byte>(value & 0x7FU);
            value >>= 7U;
            bytes_.push_back(value == 0 ? byte
                                        : static_cast<Byte>(byte | 0x80U));
        } while (value != 0);
    }

    void Bool(bool value) { bytes_.push_back(value ? 1 : 0); }

    void Float(float value) { FixedU32(std::bit_cast<std::uint32_t>(value)); }

    void HalfBits(std::uint16_t bits) {
        bytes_.push_back(static_cast<Byte>(bits));
        bytes_.push_back(static_cast<Byte>(bits >> 8U));
    }

    void String(std::string_view value) {
        VarU32(static_cast<std::uint32_t>(value.size()));
        bytes_.insert(bytes_.end(), value.begin(), value.end());
    }

    void Bytes(std::span<const Byte> value) {
        VarU32(static_cast<std::uint32_t>(value.size()));
        bytes_.insert(bytes_.end(), value.begin(), value.end());
    }

    [[nodiscard]] std::vector<Byte> Finish() && { return std::move(bytes_); }

   private:
    std::vector<Byte> bytes_;
};

void WriteEmptyStringMap(FixtureWriter& writer) {
    writer.VarU32(0);
}

void WriteGeoProperties(FixtureWriter& writer, bool half_float = false,
                        bool nonfinite = false) {
    writer.String("geometry.test");
    if (half_float) {
        writer.HalfBits(0x3D00);
    } else {
        writer.Float(nonfinite ? std::numeric_limits<float>::quiet_NaN()
                               : 1.25F);
    }
    writer.Float(64.0F);
    writer.Float(2.0F);
    writer.Float(2.0F);
    writer.VarU32(3);
    writer.Float(0.0F);
    writer.Float(1.0F);
    writer.Float(0.0F);
    writer.Float(0.7F);
    writer.Float(0.7F);
    writer.VarU32(0);  // extra info
    WriteEmptyStringMap(writer);
    writer.VarU32(0);  // initialize
    writer.VarU32(0);  // pre-animation
}

void WriteQuad(FixtureWriter& writer) {
    writer.Float(0.0F);
    writer.Float(0.0F);
    writer.Float(1.0F);
    constexpr std::array<std::array<float, 5>, 4> kVertices{{
        {0, 0, 0, 0, 0},
        {1, 0, 0, 1, 0},
        {1, 1, 0, 1, 1},
        {0, 1, 0, 0, 1},
    }};
    for (const auto& vertex : kVertices) {
        for (const auto component : vertex) {
            writer.Float(component);
        }
    }
}

void WriteGeo(FixtureWriter& writer, std::uint32_t version,
              std::string_view name, bool v5_face = false,
              bool half_float = false, std::string_view parent = {},
              bool nonfinite = false) {
    writer.VarU32(1);  // bones
    writer.String(parent);
    if (version == 5) {
        writer.VarU32(v5_face ? 1 : 0);
        if (v5_face) {
            WriteQuad(writer);
        }
    } else {
        writer.VarU32(0);  // cubes
    }
    writer.String(name);
    for (int index = 0; index < 5; ++index) {
        writer.Bool(false);
    }
    for (int index = 0; index < 6; ++index) {
        writer.Float(0.0F);
    }
    WriteGeoProperties(writer, half_float, nonfinite);
    if (version == 5) {
        writer.VarU32(v5_face ? 1 : 0);
    }
}

void WriteModelProperties(FixtureWriter& writer, std::uint32_t version) {
    writer.Float(0.7F);
    writer.Float(0.7F);
    WriteEmptyStringMap(writer);
    if (version >= 12) {
        writer.VarU32(0);  // buttons
        writer.VarU32(0);  // classifications
    }
    writer.String("skin");
    writer.String("");
    writer.Bool(false);  // free
    if (version >= 8) {
        writer.Bool(false);
    }
    if (version >= 13) {
        writer.Bool(false);
    }
    if (version >= 14) {
        writer.Bool(false);
    }
    if (version >= 17) {
        writer.Bool(false);
    }
    if (version >= 32) {
        writer.Bool(false);
    }
    if (version >= 20) {
        writer.String("");
        writer.String("");
    }
}

void WriteRgbaImage(FixtureWriter& writer, std::uint32_t version,
                    bool legacy_png) {
    constexpr std::array<Byte, 4> kPixel{0x10, 0x20, 0x30, 0xFF};
    writer.Bytes(kPixel);
    writer.VarU32(1);
    writer.VarU32(1);
    if (!legacy_png && version >= 23) {
        writer.VarU32(1);  // historical RGBA enum
        writer.VarU32(1);
    }
}

void WriteCurrentTexture(FixtureWriter& writer, std::uint32_t version) {
    writer.String("");  // source hash
    WriteRgbaImage(writer, version, false);
    writer.VarU32(0);  // PBR
}

void WritePlayer(FixtureWriter& writer, std::uint32_t version,
                 bool half_float = false, std::string_view parent = {},
                 bool nonfinite = false,
                 bool infinite_animation_length = false) {
    writer.VarU32(infinite_animation_length ? 1 : 0);  // animations
    if (infinite_animation_length) {
        writer.VarU32(1);   // main animation type
        writer.String("");  // source hash
        writer.VarU32(1);   // animations in file
        writer.String("idle");
        writer.Float(std::numeric_limits<float>::infinity());
        writer.VarU32(1);  // loop
        for (int index = 0; index < 4; ++index) {
            writer.VarU32(0);  // animation optionals
        }
        writer.VarU32(0);  // bones
        writer.VarU32(0);  // instructions
        writer.VarU32(0);  // sounds
    }
    writer.VarU32(0);  // controllers
    writer.VarU32(1);  // textures
    writer.String("skin");
    WriteCurrentTexture(writer, version);
    writer.VarU32(2);  // geos
    for (const auto [type, name] : {std::pair{1U, std::string_view("main")},
                                    std::pair{2U, std::string_view("arm")}}) {
        writer.VarU32(type);
        writer.String("");  // source hash
        WriteGeo(writer, version, name, false, half_float && type == 1,
                 type == 1 ? parent : std::string_view{},
                 nonfinite && type == 1);
    }
}

void WriteCurrent(FixtureWriter& writer, std::uint32_t version,
                  std::string_view hash, bool half_float,
                  std::string_view parent, bool nonfinite,
                  bool infinite_animation_length,
                  std::span<const SoundFixture> sounds = {}) {
    writer.VarU32(static_cast<std::uint32_t>(sounds.size()));
    for (const auto& sound : sounds) {
        writer.String(sound.name);
        writer.String("");  // source hash
        writer.Bytes(sound.bytes);
    }
    writer.VarU32(0);  // functions
    writer.VarU32(0);  // languages
    if (version >= 27) {
        writer.VarU32(0);  // vehicles
        writer.VarU32(0);  // projectiles
    } else {
        writer.VarU32(0);  // projectile map
        if (version >= 21) {
            writer.VarU32(0);  // vehicle map
        }
    }
    writer.VarU32(1);  // player
    WritePlayer(writer, version, half_float, parent, nonfinite,
                infinite_animation_length);
    writer.String(hash);
    writer.VarU32(0);  // metadata
    WriteModelProperties(writer, version);
    writer.VarU32(0);  // author avatars
    if (version >= 20) {
        writer.VarU32(0);  // GUI images
    }
    if (version >= 27) {
        writer.VarU32(0);  // origin version
    }
    writer.VarU32(0);  // export info
    writer.String("");
}

void WriteLegacyTexture(FixtureWriter& writer, std::uint32_t version) {
    if (version >= 3) {
        WriteRgbaImage(writer, version, true);
        writer.VarU32(0);  // PBR
    } else {
        writer.VarU32(1);  // optional legacy PNG
        WriteRgbaImage(writer, version, true);
    }
}

void WriteLegacyInfo(FixtureWriter& writer, std::uint32_t version) {
    writer.VarU32(0);  // metadata
    WriteModelProperties(writer, version);
}

void WriteLegacy(FixtureWriter& writer, std::uint32_t version,
                 std::string_view hash, bool v5_face, std::string_view parent,
                 bool nonfinite) {
    writer.VarU32(0);  // features
    writer.VarU32(2);  // geos
    for (const auto [type, name] : {std::pair{1U, std::string_view("main")},
                                    std::pair{2U, std::string_view("arm")}}) {
        writer.VarU32(type);
        writer.VarU32(1);  // optional geo
        WriteGeo(writer, version, name, v5_face && type == 1, false,
                 type == 1 ? parent : std::string_view{},
                 nonfinite && type == 1);
    }
    writer.VarU32(0);  // animations
    if (version >= 10) {
        writer.VarU32(0);  // controllers
        writer.VarU32(0);  // controller hashes
    }
    writer.VarU32(1);  // textures
    writer.String(version == 1 ? "skin.png" : "skin");
    WriteLegacyTexture(writer, version);
    if (version >= 11) {
        writer.VarU32(0);  // sounds
        writer.VarU32(0);  // sound hashes
    }
    if (version >= 16) {
        writer.VarU32(0);  // functions
        writer.VarU32(0);  // function hashes
    }
    if (version >= 18) {
        writer.VarU32(0);  // languages
        writer.VarU32(0);  // language hashes
    }
    if (version >= 4) {
        writer.VarU32(0);  // avatars
    }
    writer.VarU32(0);  // geo hashes
    writer.VarU32(0);  // animation hashes
    writer.VarU32(1);  // texture hashes
    writer.String(version == 1 ? "skin.png" : "skin");
    if (version >= 3) {
        writer.String("");
        writer.VarU32(0);  // PBR hashes
    } else {
        writer.String("");
    }
    writer.String(hash);
    if (version >= 2) {
        writer.VarU32(1);
        WriteLegacyInfo(writer, version);
    }
    if (version >= 6) {
        writer.VarU32(0);  // export info
    }
}

std::vector<Byte> MakeFixture(std::uint32_t version,
                              std::string_view hash = kModelHash,
                              bool v5_face = false, bool half_float = false,
                              std::string_view parent = {},
                              bool nonfinite = false,
                              bool infinite_animation_length = false) {
    FixtureWriter writer;
    writer.FixedU32(version);
    if (version < 19) {
        WriteLegacy(writer, version, hash, v5_face, parent, nonfinite);
    } else {
        WriteCurrent(writer, version, hash, half_float, parent, nonfinite,
                     infinite_animation_length);
    }
    return std::move(writer).Finish();
}

std::vector<Byte> MakeCurrentSoundFixture(std::span<const Byte> sound) {
    FixtureWriter writer;
    writer.FixedU32(32);
    const std::array sounds{SoundFixture{"sound", sound}};
    WriteCurrent(writer, 32, kModelHash, false, {}, false, false, sounds);
    return std::move(writer).Finish();
}


int main(int argc,char** argv) {
 std::filesystem::path out(argv[1]);
 auto emit=[&](std::string name, std::vector<Byte> data){std::ofstream f(out/name,std::ios::binary);f.write(reinterpret_cast<const char*>(data.data()),data.size());};
 for(unsigned v=1;v<=32;v++)emit("v"+std::to_string(v)+".wire",MakeFixture(v));
 emit("v5-face.wire",MakeFixture(5,kModelHash,true));
 emit("v32-infinite-animation.wire",MakeFixture(32,kModelHash,false,false,{},false,true));
}
