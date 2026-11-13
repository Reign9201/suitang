import XCTest
@testable import BiliStudy

/// 跑 docs/sync-cases.json 里和 Android 共用的合并用例。
final class SyncMergeTests: XCTestCase {
    private struct Fixture: Decodable {
        struct Case: Decodable { var name: String; var sets: [SyncSet]; var expected: SyncSet }
        var now: Int64
        var cases: [Case]
    }

    func testSharedCases() throws {
        let url = try XCTUnwrap(Bundle(for: Self.self).url(forResource: "sync-cases", withExtension: "json"))
        let fixture = try JSONDecoder().decode(Fixture.self, from: Data(contentsOf: url))
        XCTAssertFalse(fixture.cases.isEmpty)
        for c in fixture.cases {
            XCTAssertEqual(SyncSet.merge(c.sets, now: fixture.now), c.expected, c.name)
            XCTAssertEqual(SyncSet.merge(c.sets.reversed(), now: fixture.now), c.expected, c.name + "（倒序）")
        }
    }

    func testDocumentRoundTrip() throws {
        let saved = SavedCourse(bvid: "BV18pWGzbEvh", title: "七上数学", cover: "", author: "a", duration: 10, episodes: 56,
                                grade: 7, subject: "数学", volume: "上册")
        let doc = SyncDocument(device: "d", deviceName: "iPad", savedAt: 1,
                               sets: ["favorites": SyncSet(items: ["BV18pWGzbEvh": SyncItem(at: 5, data: .from(saved))]),
                                      "future-feature": SyncSet(items: ["x": SyncItem(at: 1, data: .object(["k": .number(1)]))])])
        let back = try JSONDecoder().decode(SyncDocument.self, from: JSONEncoder().encode(doc))
        XCTAssertEqual(back.sets["favorites"]?.items["BV18pWGzbEvh"]?.data.decode(SavedCourse.self), saved)
        // 不认识的集合原样保留
        XCTAssertEqual(back.sets["future-feature"], doc.sets["future-feature"])
    }

    func testPropfindParsing() {
        let xml = """
        <?xml version="1.0" encoding="utf-8"?><D:multistatus xmlns:D="DAV:">
        <D:response><D:href>/dav/bilistudy/</D:href><D:propstat><D:prop><D:getetag/></D:prop></D:propstat></D:response>
        <D:response><D:href>/dav/bilistudy/ABC.json</D:href><D:propstat><D:prop><D:getetag>"e1"</D:getetag></D:prop></D:propstat></D:response>
        </D:multistatus>
        """
        XCTAssertEqual(PropfindParser.parse(Data(xml.utf8)), [RemoteFile(name: "ABC.json", etag: "\"e1\"")])
    }
}
