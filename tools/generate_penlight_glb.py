#!/usr/bin/env python3
"""Generate Spright's exhibition penlight GLB using only the Python standard library."""

from __future__ import annotations

import json
import math
import struct
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / "app" / "src" / "main" / "assets" / "models" / "spright_penlight.glb"
SEGMENTS = 48


def pad4(data: bytes, byte: bytes = b"\x00") -> bytes:
    return data + byte * ((-len(data)) % 4)


def cylinder(radius: float, height: float) -> tuple[list[float], list[float], list[int]]:
    positions: list[float] = []
    normals: list[float] = []
    indices: list[int] = []
    half = height / 2.0

    # Side wall, with a duplicated seam for clean normals.
    for i in range(SEGMENTS + 1):
        angle = math.tau * i / SEGMENTS
        x, z = radius * math.cos(angle), radius * math.sin(angle)
        nx, nz = math.cos(angle), math.sin(angle)
        positions.extend((x, -half, z, x, half, z))
        normals.extend((nx, 0.0, nz, nx, 0.0, nz))
    for i in range(SEGMENTS):
        a, b = i * 2, i * 2 + 1
        c, d = (i + 1) * 2, (i + 1) * 2 + 1
        indices.extend((a, c, b, b, c, d))

    # End caps.
    for y, ny, reverse in ((-half, -1.0, True), (half, 1.0, False)):
        center = len(positions) // 3
        positions.extend((0.0, y, 0.0))
        normals.extend((0.0, ny, 0.0))
        ring = len(positions) // 3
        for i in range(SEGMENTS):
            angle = math.tau * i / SEGMENTS
            positions.extend((radius * math.cos(angle), y, radius * math.sin(angle)))
            normals.extend((0.0, ny, 0.0))
        for i in range(SEGMENTS):
            a, b = ring + i, ring + (i + 1) % SEGMENTS
            indices.extend((center, b, a) if reverse else (center, a, b))
    return positions, normals, indices


def radial_profile(profile: list[tuple[float, float]]) -> tuple[list[float], list[float], list[int]]:
    """Create a smooth surface of revolution around Y from (y, radius) rings."""
    positions: list[float] = []
    normals: list[float] = []
    indices: list[int] = []
    for ring_index, (y, radius) in enumerate(profile):
        previous = profile[max(0, ring_index - 1)]
        following = profile[min(len(profile) - 1, ring_index + 1)]
        dy = following[0] - previous[0]
        dr_dy = (following[1] - previous[1]) / dy if abs(dy) > 1e-9 else 0.0
        normal_length = math.sqrt(1.0 + dr_dy * dr_dy)
        for i in range(SEGMENTS + 1):
            angle = math.tau * i / SEGMENTS
            cos_angle, sin_angle = math.cos(angle), math.sin(angle)
            positions.extend((radius * cos_angle, y, radius * sin_angle))
            normals.extend(
                (
                    cos_angle / normal_length,
                    -dr_dy / normal_length,
                    sin_angle / normal_length,
                )
            )
    stride = SEGMENTS + 1
    for ring in range(len(profile) - 1):
        for segment in range(SEGMENTS):
            a = ring * stride + segment
            b = a + stride
            indices.extend((a, a + 1, b, a + 1, b + 1, b))
    return positions, normals, indices


def capsule(radius: float, straight_height: float, cap_rings: int = 8) -> tuple[list[float], list[float], list[int]]:
    half_straight = straight_height / 2.0
    profile: list[tuple[float, float]] = []
    for i in range(cap_rings + 1):
        angle = -math.pi / 2.0 + (math.pi / 2.0) * i / cap_rings
        profile.append((-half_straight + radius * math.sin(angle), radius * math.cos(angle)))
    for i in range(1, cap_rings + 1):
        angle = (math.pi / 2.0) * i / cap_rings
        profile.append((half_straight + radius * math.sin(angle), radius * math.cos(angle)))
    return radial_profile(profile)


def box(size_x: float, size_y: float, size_z: float) -> tuple[list[float], list[float], list[int]]:
    hx, hy, hz = size_x / 2, size_y / 2, size_z / 2
    faces = (
        ((1, 0, 0), ((hx, -hy, -hz), (hx, -hy, hz), (hx, hy, hz), (hx, hy, -hz))),
        ((-1, 0, 0), ((-hx, -hy, hz), (-hx, -hy, -hz), (-hx, hy, -hz), (-hx, hy, hz))),
        ((0, 1, 0), ((-hx, hy, -hz), (hx, hy, -hz), (hx, hy, hz), (-hx, hy, hz))),
        ((0, -1, 0), ((-hx, -hy, hz), (hx, -hy, hz), (hx, -hy, -hz), (-hx, -hy, -hz))),
        ((0, 0, 1), ((hx, -hy, hz), (-hx, -hy, hz), (-hx, hy, hz), (hx, hy, hz))),
        ((0, 0, -1), ((-hx, -hy, -hz), (hx, -hy, -hz), (hx, hy, -hz), (-hx, hy, -hz))),
    )
    positions: list[float] = []
    normals: list[float] = []
    indices: list[int] = []
    for normal, corners in faces:
        base = len(positions) // 3
        for corner in corners:
            positions.extend(corner)
            normals.extend(normal)
        indices.extend((base, base + 1, base + 2, base, base + 2, base + 3))
    return positions, normals, indices


def main() -> None:
    binary = bytearray()
    buffer_views: list[dict] = []
    accessors: list[dict] = []
    meshes: list[dict] = []

    materials = [
        {
            "name": "HandleMaterial",
            "pbrMetallicRoughness": {
                "baseColorFactor": [0.018, 0.024, 0.035, 1.0],
                "metallicFactor": 0.38,
                "roughnessFactor": 0.4,
            },
        },
        {
            "name": "MetalMaterial",
            "pbrMetallicRoughness": {
                "baseColorFactor": [0.075, 0.095, 0.125, 1.0],
                "metallicFactor": 0.82,
                "roughnessFactor": 0.3,
            },
        },
        {
            "name": "ButtonMaterial",
            "pbrMetallicRoughness": {
                "baseColorFactor": [0.08, 0.16, 0.22, 1.0],
                "metallicFactor": 0.42,
                "roughnessFactor": 0.25,
            },
            "emissiveFactor": [0.04, 0.22, 0.34],
        },
        {
            "name": "EmitterMaterial",
            "pbrMetallicRoughness": {
                "baseColorFactor": [1.0, 1.0, 1.0, 0.64],
                "metallicFactor": 0.0,
                "roughnessFactor": 0.16,
            },
            "emissiveFactor": [1.0, 1.0, 1.0],
            "alphaMode": "BLEND",
            "doubleSided": True,
        },
        {
            "name": "GlowMaterial",
            "pbrMetallicRoughness": {
                "baseColorFactor": [1.0, 1.0, 1.0, 0.035],
                "metallicFactor": 0.0,
                "roughnessFactor": 0.0,
            },
            "emissiveFactor": [1.0, 1.0, 1.0],
            "alphaMode": "BLEND",
            "doubleSided": True,
        },
        {
            "name": "YawMarkerMaterial",
            "pbrMetallicRoughness": {
                "baseColorFactor": [0.72, 0.82, 0.94, 1.0],
                "metallicFactor": 0.46,
                "roughnessFactor": 0.24,
            },
            "emissiveFactor": [0.12, 0.18, 0.26],
        },
    ]

    def add_blob(data: bytes, target: int) -> int:
        offset = len(binary)
        binary.extend(data)
        binary.extend(b"\x00" * ((-len(binary)) % 4))
        buffer_views.append(
            {"buffer": 0, "byteOffset": offset, "byteLength": len(data), "target": target}
        )
        return len(buffer_views) - 1

    def add_mesh(name: str, geometry: tuple[list[float], list[float], list[int]], material: int) -> int:
        positions, normals, indices = geometry
        position_view = add_blob(struct.pack(f"<{len(positions)}f", *positions), 34962)
        normal_view = add_blob(struct.pack(f"<{len(normals)}f", *normals), 34962)
        index_view = add_blob(struct.pack(f"<{len(indices)}H", *indices), 34963)
        points = list(zip(positions[0::3], positions[1::3], positions[2::3]))
        accessors.extend(
            (
                {
                    "bufferView": position_view,
                    "componentType": 5126,
                    "count": len(points),
                    "type": "VEC3",
                    "min": [min(p[i] for p in points) for i in range(3)],
                    "max": [max(p[i] for p in points) for i in range(3)],
                },
                {
                    "bufferView": normal_view,
                    "componentType": 5126,
                    "count": len(normals) // 3,
                    "type": "VEC3",
                },
                {
                    "bufferView": index_view,
                    "componentType": 5123,
                    "count": len(indices),
                    "type": "SCALAR",
                    "min": [min(indices)],
                    "max": [max(indices)],
                },
            )
        )
        primitive = {
            "attributes": {"POSITION": len(accessors) - 3, "NORMAL": len(accessors) - 2},
            "indices": len(accessors) - 1,
            "material": material,
        }
        meshes.append({"name": name + "Mesh", "primitives": [primitive]})
        return len(meshes) - 1

    handle_mesh = add_mesh(
        "Handle",
        radial_profile(
            [
                (-0.055, 0.0155),
                (-0.051, 0.0180),
                (-0.043, 0.0190),
                (0.038, 0.0190),
                (0.047, 0.0180),
                (0.053, 0.0160),
            ]
        ),
        0,
    )
    collar_mesh = add_mesh(
        "Collar",
        radial_profile(
            [
                (-0.009, 0.0160),
                (-0.007, 0.0195),
                (0.005, 0.0195),
                (0.009, 0.0160),
            ]
        ),
        1,
    )
    endcap_mesh = add_mesh(
        "EndCap",
        radial_profile(
            [
                (-0.007, 0.0130),
                (-0.005, 0.0175),
                (0.005, 0.0175),
                (0.008, 0.0155),
            ]
        ),
        1,
    )
    button_mesh = add_mesh("Button", cylinder(0.006, 0.004), 2)
    # A visibly hemispherical cap is important for reading the model's attitude
    # against the dark exhibition background.
    emitter_mesh = add_mesh("Emitter", capsule(0.0160, 0.184), 3)
    glow_mesh = add_mesh("GlowShell", capsule(0.0205, 0.188), 4)
    accent_mesh = add_mesh("AccentRing", cylinder(0.0194, 0.003), 1)
    button_ring_mesh = add_mesh("ButtonRing", cylinder(0.008, 0.002), 1)
    yaw_marker_stem_mesh = add_mesh("YawMarker", box(0.0035, 0.019, 0.0018), 5)
    yaw_marker_wing_mesh = add_mesh("YawMarkerWing", box(0.0032, 0.011, 0.0018), 5)

    nodes = [
        {"name": "SprightRoot", "children": list(range(1, 12))},
        {"name": "Handle", "mesh": handle_mesh, "translation": [0.0, -0.110, 0.0]},
        {"name": "Collar", "mesh": collar_mesh, "translation": [0.0, -0.052, 0.0]},
        {
            "name": "Button",
            "mesh": button_mesh,
            "translation": [0.0, -0.106, -0.020],
            "rotation": [0.7071068, 0.0, 0.0, 0.7071068],
        },
        {"name": "EndCap", "mesh": endcap_mesh, "translation": [0.0, -0.170, 0.0]},
        {"name": "Emitter", "mesh": emitter_mesh, "translation": [0.0, 0.061, 0.0]},
        {"name": "GlowShell", "mesh": glow_mesh, "translation": [0.0, 0.061, 0.0]},
        {"name": "AccentRing", "mesh": accent_mesh, "translation": [0.0, -0.137, 0.0]},
        {
            "name": "ButtonRing",
            "mesh": button_ring_mesh,
            "translation": [0.0, -0.106, -0.019],
            "rotation": [0.7071068, 0.0, 0.0, 0.7071068],
        },
        {
            "name": "YawMarker",
            "mesh": yaw_marker_stem_mesh,
            "translation": [0.0, -0.108, 0.0195],
        },
        {
            "name": "YawMarkerLeft",
            "mesh": yaw_marker_wing_mesh,
            "translation": [-0.0034, -0.0955, 0.0195],
            "rotation": [0.0, 0.0, -0.3420201, 0.9396926],
        },
        {
            "name": "YawMarkerRight",
            "mesh": yaw_marker_wing_mesh,
            "translation": [0.0034, -0.0955, 0.0195],
            "rotation": [0.0, 0.0, 0.3420201, 0.9396926],
        },
    ]

    document = {
        "asset": {"version": "2.0", "generator": "Spright standard-library GLB generator"},
        "scene": 0,
        "scenes": [{"name": "SprightScene", "nodes": [0]}],
        "nodes": nodes,
        "meshes": meshes,
        "materials": materials,
        "accessors": accessors,
        "bufferViews": buffer_views,
        "buffers": [{"byteLength": len(binary)}],
    }
    json_chunk = pad4(json.dumps(document, separators=(",", ":")).encode("utf-8"), b" ")
    bin_chunk = pad4(bytes(binary))
    total_length = 12 + 8 + len(json_chunk) + 8 + len(bin_chunk)
    glb = bytearray(struct.pack("<4sII", b"glTF", 2, total_length))
    glb.extend(struct.pack("<I4s", len(json_chunk), b"JSON"))
    glb.extend(json_chunk)
    glb.extend(struct.pack("<I4s", len(bin_chunk), b"BIN\x00"))
    glb.extend(bin_chunk)

    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    OUTPUT.write_bytes(glb)
    print(f"Wrote {OUTPUT} ({len(glb)} bytes)")


if __name__ == "__main__":
    main()
