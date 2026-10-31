"""Host arithmetic/policy fixtures bind to actual native expressions; no native execution is claimed."""
import ast
import importlib.util
from fractions import Fraction
from pathlib import Path
import re
import shutil
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

NATIVE = Path(__file__).resolve().parents[1] / "native/media-decoder"
SOURCE = (NATIVE / "media_decoder.cpp").read_text()


def expression(name):
    match = re.search(r"(?:const int(?:64_t)?|const bool) " + name + r"\s*=\s*(.*?);", SOURCE, re.S)
    if not match:
        raise ValueError("native expression missing: " + name)
    return match[1]


def calculate(text, **values):
    """Interpret only the integer/Boolean expression vocabulary present in these native fixtures."""
    text = text.replace("std::max", "max").replace("->", ".")
    text = text.replace("&&", " and ").replace("||", " or ")
    text = re.sub(r"!(?!=)", " not ", text).replace("/", "//")
    tree = ast.parse(" ".join(text.split()), mode="eval")

    def visit(node):
        if isinstance(node, ast.Expression):
            return visit(node.body)
        if isinstance(node, ast.Constant) and type(node.value) is int:
            return node.value
        if isinstance(node, ast.Name):
            return values[node.id]
        if isinstance(node, ast.Attribute) and node.attr in {"num", "den", "width", "height", "dv_profile",
                "bl_present_flag", "el_present_flag", "dv_bl_signal_compatibility_id", "color_trc", "colorspace", "color_primaries"}:
            return getattr(visit(node.value), node.attr)
        if isinstance(node, ast.Call) and isinstance(node.func, ast.Name):
            functions = {"int": int, "int64_t": int, "max": max}
            return functions[node.func.id](*(visit(arg) for arg in node.args))
        if isinstance(node, ast.BinOp):
            operations = {ast.Add: lambda a, b: a + b, ast.Sub: lambda a, b: a - b,
                          ast.Mult: lambda a, b: a * b, ast.FloorDiv: lambda a, b: a // b}
            return operations[type(node.op)](visit(node.left), visit(node.right))
        if isinstance(node, ast.BoolOp):
            return (all if isinstance(node.op, ast.And) else any)(visit(value) for value in node.values)
        if isinstance(node, ast.UnaryOp) and isinstance(node.op, ast.Not):
            return not visit(node.operand)
        if isinstance(node, ast.Compare) and len(node.ops) == 1:
            operations = {ast.Eq: lambda a, b: a == b, ast.Lt: lambda a, b: a < b,
                          ast.LtE: lambda a, b: a <= b, ast.Gt: lambda a, b: a > b}
            return operations[type(node.ops[0])](visit(node.left), visit(node.comparators[0]))
        raise ValueError("unreviewed native expression construct: " + ast.dump(node))

    return visit(tree)


def constant(name):
    return int(re.search(r"constexpr (?:int64_t|int) " + name + r" = (\d+);", SOURCE)[1])


def dependency_guess(stream, frame, codec):
    """Model pinned av_guess_sample_aspect_ratio's av_reduce/sanitize/selection chain, not native execution."""
    def normalized(pair):
        num, den = pair
        if den == 0:
            return 0, 1
        ratio = Fraction(num, den)  # av_reduce also normalizes denominator signs and common factors.
        return (ratio.numerator, ratio.denominator) if ratio.numerator > 0 else (0, 1)

    container = normalized(stream)
    fallback = normalized(frame if frame is not None else codec)
    return container if container[0] else fallback


def display_chain(width, height, stream, frame=None, codec=(0, 1)):
    if not (0 < width <= constant("kMaxDimension") and 0 < height <= constant("kMaxDimension") and
            width * height <= constant("kMaxSourcePixels")):
        raise ValueError("source refused")
    body = SOURCE.split("DisplayGeometry displayGeometry", 1)[1].split("DisplayGeometry conversionGeometry", 1)[0]
    unknown = re.search(r"if \((rawSar\.num.*?)\) \{", body)[1]
    raw = stream
    if calculate(unknown, rawSar=SimpleNamespace(num=raw[0], den=raw[1])):
        raw = frame if frame is not None else codec
    guard = re.search(r"check\((\(rawSar\.num.*?),\s*\"sample_aspect_ratio_refused\"\);", body, re.S)[1]
    if not calculate(guard, rawSar=SimpleNamespace(num=raw[0], den=raw[1])):
        raise ValueError("raw SAR refused before dependency normalization")
    num, den = dependency_guess(stream, frame, codec)
    if num == 0 and den == 1:
        num, den = 1, 1
    result = calculate(expression("displayWidth"), width=width, sar=SimpleNamespace(num=num, den=den))
    if not (0 < result <= constant("kMaxDimension") and result * height <= constant("kMaxSourcePixels")):
        raise ValueError("display refused")
    return result, height


def display(width, height, num, den):
    return display_chain(width, height, (num, den))


def fit(size, edge):
    dw, dh = size
    longest = max(size)
    low, high = 1, min(edge, longest)
    fitted = (1, 1)
    while low <= high:
        candidate = low + (high - low) // 2
        context = {"display": SimpleNamespace(width=dw, height=dh), "candidate": candidate, "longest": longest}
        for key in ("width", "height", "alignedWidth", "alignedHeight", "footprint"):
            # width/height occur first in displayGeometry, so select only the conversion helper body.
            body = SOURCE.split("DisplayGeometry conversionGeometry", 1)[1].split("void validateVideoColor", 1)[0]
            value = re.search(r"const int(?:64_t)? " + key + r" = (.*?);", body, re.S)[1]
            context[key] = calculate(value, **context, kConversionPadding=constant("kConversionPadding"))
        condition = re.search(r"if \((int64_t\(width\).*?)\) \{", body, re.S)[1]
        allocation = re.search(r"constexpr size_t kMaxAllocation = (.*?);", SOURCE)[1].replace("U", "")
        if calculate(condition, **context, kMaxConversionPixels=constant("kMaxConversionPixels"),
                     kMaxAllocation=calculate(allocation)):
            fitted = context["width"], context["height"]
            low = candidate + 1
        else:
            high = candidate - 1
    return fitted


class MediaVideoGeometryTests(unittest.TestCase):
    def test_anamorphic_display_geometry_and_rotated_poster_dimensions(self):
        for coded, sar, expected in [((720, 576), (16, 15), (768, 576)),
                                     ((720, 576), (64, 45), (1024, 576)),
                                     ((720, 480), (8, 9), (640, 480))]:
            with self.subTest(coded=coded, sar=sar):
                self.assertEqual(expected, display(*coded, *sar))
        self.assertEqual((512, 384), fit(display(720, 576, 16, 15), 512))
        self.assertEqual((384, 512), tuple(reversed(fit(display(720, 576, 16, 15), 512))))

    def test_ordinary_square_pixels_and_common_four_k_retain_resolution(self):
        for width, height in ((1920, 1080), (3840, 2160), (2160, 3840)):
            self.assertEqual((width, height), fit(display(width, height, 1, 1), 4096))
        self.assertEqual((720, 576), display(720, 576, 0, 1))

    def test_conversion_budget_reduces_texture_without_cropping_display_aspect(self):
        for size, expected in [((4096, 2160), (3968, 2092)), ((4096, 4096), (2880, 2880))]:
            fitted = fit(size, 4096)
            self.assertEqual(expected, fitted)
            self.assertLessEqual(fitted[0] * fitted[1], 8388608)
            self.assertLessEqual(abs(fitted[0] * size[1] - fitted[1] * size[0]), max(size))
        for width in (1, 33, 720, 1920, 4096, 32768):
            for height in (1, 33, 576, 1080, 2160, 4096):
                for edge in (1, 127, 512, 4096):
                    result = fit((width, height), edge)
                    self.assertTrue(1 <= max(result) <= edge)
                    self.assertLessEqual(result[0] * result[1], 8388608)

    def test_invalid_and_extreme_sar_cannot_overflow_or_invent_display_dimensions(self):
        for values in ((720, 576, -1, 1), (720, 576, 1, 0), (32768, 1, 2147483647, 1),
                       (32768, 1, 1, 2147483647), (32769, 1, 1, 1)):
            with self.subTest(values=values), self.assertRaises(ValueError):
                display(*values)

    def test_raw_validation_precedes_dependency_normalization_in_the_actual_chain(self):
        body = SOURCE.split("DisplayGeometry displayGeometry", 1)[1].split("DisplayGeometry conversionGeometry", 1)[0]
        self.assertLess(body.index('"sample_aspect_ratio_refused"'), body.index("av_guess_sample_aspect_ratio"))
        self.assertIn("rawSar = stream->sample_aspect_ratio", body)
        self.assertIn("rawSar = frame ? frame->sample_aspect_ratio : stream->codecpar->sample_aspect_ratio", body)
        for invalid in ((-1, 1), (1, 0), (0, 0), (0, -1), (-1, -1)):
            with self.subTest(invalid=invalid):
                # The old wrapper would normalize these into unknown or a positive ratio, hiding malformed input.
                self.assertEqual((1, 1) if invalid == (-1, -1) else (0, 1),
                                 dependency_guess(invalid, None, (0, 1)))
                with self.assertRaisesRegex(ValueError, "before dependency normalization"):
                    display_chain(720, 576, invalid)
                with self.assertRaisesRegex(ValueError, "before dependency normalization"):
                    display_chain(720, 576, (0, 1), frame=invalid)
                with self.assertRaisesRegex(ValueError, "before dependency normalization"):
                    display_chain(720, 576, (0, 1), codec=invalid)
        self.assertEqual((16, 15), dependency_guess((-1, 1), (16, 15), (0, 1)))
        with self.assertRaisesRegex(ValueError, "before dependency normalization"):
            display_chain(720, 576, (-1, 1), frame=(16, 15))
        self.assertEqual((720, 576), display_chain(720, 576, (0, 1)))
        self.assertEqual((768, 576), display_chain(720, 576, (0, 1), frame=(16, 15)))
        self.assertEqual((768, 576), display_chain(720, 576, (0, 1), codec=(16, 15)))
        # A valid preferred container ratio wins over unselected frame metadata, as in FFmpeg.
        self.assertEqual((768, 576), display_chain(720, 576, (16, 15), frame=(1, 0)))

    def test_info_and_render_use_the_same_display_geometry_before_rotation(self):
        info = SOURCE.split("NativeMediaEngine_nativeInfo", 1)[1].split("NativeMediaEngine_nativeRead", 1)[0]
        self.assertIn("const auto display = displayGeometry(s, nullptr)", info)
        video = SOURCE.split("jlongArray video(", 1)[1].split("void configureResampler", 1)[0]
        self.assertIn("displayGeometry(s, frame)", video)
        self.assertIn("conversionGeometry(display, s.edge)", video)
        self.assertNotIn("sws_getCachedContext", SOURCE)
        self.assertNotIn("sws_init_context", SOURCE)
        self.assertNotIn("sws_scale(", SOURCE)

    def test_perceptual_cms_follows_bounded_same_encoding_scale_and_emits_srgb(self):
        video = SOURCE.split("jlongArray video(", 1)[1].split("void configureResampler", 1)[0]
        self.assertIn("av_frame_copy_props(converted.scaled, frame)", video)
        self.assertLess(video.index("sws_scale_frame(dynamicScaler(s.scale)"),
                        video.index("sws_scale_frame(dynamicScaler(s.colors)"))
        self.assertNotIn("av_frame_copy_props(converted.rgba", video)
        for field, value in (("format", "AV_PIX_FMT_RGBA"), ("color_range", "AVCOL_RANGE_JPEG"),
                             ("colorspace", "AVCOL_SPC_RGB"), ("color_primaries", "AVCOL_PRI_BT709"),
                             ("color_trc", "AVCOL_TRC_IEC61966_2_1")):
            self.assertIn("converted.rgba->" + field + " = " + value, video)
        for setting in ("context->intent = SWS_INTENT_PERCEPTUAL", "context->threads = 1",
                        "context->backends = SWS_BACKEND_STABLE"):
            self.assertIn(setting, SOURCE)
        self.assertNotIn("SWS_UNSTABLE", SOURCE)
        self.assertIn("converted.rgba->linesize[0]", video)
        self.assertIn("size_t(width) * 4", video)

    def test_dolby_vision_base_layer_policy_rejects_profile_five_and_unknown_matrices(self):
        condition = re.search(r"check\((config->dv_profile.*?), \"unsupported_dolby_vision_profile\"\);", SOURCE, re.S)[1]
        for profile, compatibility, transfer, matrix, enhancement, expected in (
                (8, 1, 16, 9, 0, True), (8, 4, 18, 9, 0, True), (5, 1, 16, 9, 0, False),
                (8, 1, 18, 9, 0, False), (8, 4, 16, 9, 0, False), (8, 1, 16, 15, 0, False),
                (8, 1, 16, 2, 0, False), (8, 1, 16, 9, 1, False)):
            config = SimpleNamespace(dv_profile=profile, bl_present_flag=1, el_present_flag=enhancement,
                                     dv_bl_signal_compatibility_id=compatibility)
            frame = SimpleNamespace(colorspace=matrix, color_primaries=9, color_trc=transfer)
            result = calculate(condition, config=config, frame=frame,
                               pqBase=calculate(expression("pqBase"), config=config, frame=frame, AVCOL_TRC_SMPTE2084=16),
                               hlgBase=calculate(expression("hlgBase"), config=config, frame=frame, AVCOL_TRC_ARIB_STD_B67=18),
                               AVCOL_SPC_BT2020_NCL=9, AVCOL_PRI_BT2020=9)
            self.assertEqual(expected, bool(result))
        self.assertIn('"unsupported_video_color_metadata"', SOURCE)
        self.assertIn('"underspecified_hdr_color_metadata"', SOURCE)

    def test_color_recipe_change_invalidates_native_artifact_cache(self):
        spec = importlib.util.spec_from_file_location("video_cache_fixture", Path(__file__).with_name("prepare_media_decoder.py"))
        build = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(build)
        with tempfile.TemporaryDirectory() as temporary:
            native = Path(temporary) / "native"
            shutil.copytree(NATIVE, native)
            with patch.object(build, "NATIVE", native):
                first = build.cache_key(["arm64-v8a"])
                wrapper = native / "media_decoder.cpp"
                wrapper.write_text(wrapper.read_text().replace("SWS_INTENT_PERCEPTUAL", "SWS_INTENT_RELATIVE_COLORIMETRIC"))
                self.assertNotEqual(first, build.cache_key(["arm64-v8a"]))


if __name__ == "__main__":
    unittest.main()
