import Foundation

/// The offline base map (Basemap.bin, built by Tools/build_context.py from Natural Earth):
/// land and lake polygons and river lines, each at a coarse and a fine level of detail.
public struct Basemap: Sendable {
    public enum Kind: UInt8, Sendable { case land = 0, lake = 1, river = 2 }
    public enum Detail: UInt8, Sendable { case coarse = 0, fine = 1 }

    public struct Ring: Sendable {
        /// Natural Earth scale rank: lower is more prominent.
        public let rank: Int
        /// Longitude, latitude pairs (x, y).
        public let points: [SIMD2<Double>]
    }

    public struct Layer: Sendable {
        public let kind: Kind
        public let detail: Detail
        public let rings: [Ring]
    }

    public enum DecodeError: Error { case badHeader, truncated }

    /// Longitude/latitude box the coordinates were quantized over.
    public let minLongitude: Double
    public let minLatitude: Double
    public let maxLongitude: Double
    public let maxLatitude: Double
    public let layers: [Layer]

    public func layer(_ kind: Kind, _ detail: Detail) -> Layer? {
        layers.first { $0.kind == kind && $0.detail == detail }
    }

    public init(data: Data) throws {
        var reader = Reader(data: data)
        guard try reader.bytes(4) == Array("SABM".utf8) else { throw DecodeError.badHeader }
        let version: UInt16 = try reader.read()
        guard version == 1 else { throw DecodeError.badHeader }
        let layerCount: UInt16 = try reader.read()
        let x0 = Double(try reader.read() as Float32)
        let y0 = Double(try reader.read() as Float32)
        let x1 = Double(try reader.read() as Float32)
        let y1 = Double(try reader.read() as Float32)
        minLongitude = x0
        minLatitude = y0
        maxLongitude = x1
        maxLatitude = y1
        let sx = (x1 - x0) / 65535
        let sy = (y1 - y0) / 65535
        var layers: [Layer] = []
        for _ in 0..<layerCount {
            let kind: UInt8 = try reader.read()
            let detail: UInt8 = try reader.read()
            let _: UInt16 = try reader.read()
            let ringCount: UInt32 = try reader.read()
            var rings: [Ring] = []
            rings.reserveCapacity(Int(ringCount))
            for _ in 0..<ringCount {
                let count: UInt32 = try reader.read()
                let rank: UInt8 = try reader.read()
                let _: UInt8 = try reader.read()
                let _: UInt16 = try reader.read()
                var points: [SIMD2<Double>] = []
                points.reserveCapacity(Int(count))
                for _ in 0..<count {
                    let x: UInt16 = try reader.read()
                    let y: UInt16 = try reader.read()
                    points.append(SIMD2(x0 + Double(x) * sx, y0 + Double(y) * sy))
                }
                rings.append(Ring(rank: Int(rank), points: points))
            }
            if let kind = Kind(rawValue: kind), let detail = Detail(rawValue: detail) {
                layers.append(Layer(kind: kind, detail: detail, rings: rings))
            }
        }
        self.layers = layers
    }

    private struct Reader {
        let data: Data
        var offset = 0

        mutating func read<T: FixedWidthInteger>() throws -> T {
            let size = MemoryLayout<T>.size
            guard offset + size <= data.count else { throw DecodeError.truncated }
            let value = data.withUnsafeBytes { $0.loadUnaligned(fromByteOffset: offset, as: T.self) }
            offset += size
            return T(littleEndian: value)
        }

        mutating func read() throws -> Float32 {
            Float32(bitPattern: try read() as UInt32)
        }

        mutating func bytes(_ count: Int) throws -> [UInt8] {
            guard offset + count <= data.count else { throw DecodeError.truncated }
            defer { offset += count }
            return Array(data[data.startIndex + offset ..< data.startIndex + offset + count])
        }
    }
}
