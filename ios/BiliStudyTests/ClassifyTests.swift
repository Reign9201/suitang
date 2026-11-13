import XCTest
@testable import BiliStudy

/// 和 Python 原型 classify.py 用同样的样例，保证两边规则一致。
final class ClassifyTests: XCTestCase {
    func testGrades() {
        XCTAssertEqual(Classify.grades("初中数学7年级上册").strong, [7])
        XCTAssertEqual(Classify.grades("提高一倍成绩 初一上册").strong, [7])
        XCTAssertEqual(Classify.grades("【56集全】人教版七上数学新教材").strong, [7])
        XCTAssertEqual(Classify.grades("初一到初三全套").strong, [7, 8, 9])
        XCTAssertEqual(Classify.grades("1-6年级奥数").strong, Set(1...6))
        XCTAssertEqual(Classify.grades("七年级数学 直击中考").strong, [7])
        XCTAssertEqual(Classify.grades("高考数学一轮复习").weak, [12])
        XCTAssertEqual(Classify.grades("高中物理必修一").weak, [10])
    }

    func testVolumesAndEpisodes() {
        XCTAssertEqual(Classify.volumes("七年级上下册2026最新版"), ["上册", "下册"])
        XCTAssertEqual(Classify.volumes("初一上/下册"), ["上册", "下册"])
        XCTAssertEqual(Classify.volumes("高中语文必修上册"), ["必修一"])
        XCTAssertEqual(Classify.volumes("选择性必修第二册"), ["选择性必修二"])
        XCTAssertEqual(Classify.episodes("【初一数学170讲】七年级数学全集"), 170)
        XCTAssertEqual(Classify.episodes("（100P，30+h）"), 100)
        XCTAssertEqual(Classify.episodes("第12讲 有理数"), 0)
        XCTAssertEqual(Classify.episodes("全189集【初一数学】"), 189)
        XCTAssertEqual(Classify.duration("1239:55"), 1239 * 60 + 55)
    }

    func testSubjectsAndEditions() {
        XCTAssertEqual(Classify.subjects("高中思想政治必修一"), ["道德与法治"])
        XCTAssertEqual(Classify.editions("人教版七年级语文上册", subject: "语文"), ["统编版"])
        XCTAssertEqual(Classify.editions("北师大版 七年级数学", subject: "数学"), ["北师大版"])
    }

    func testReject() {
        func course(_ title: String, typeid: Int = 208, tags: String = "") -> Course {
            Course(bvid: "BV1234567890", title: title, author: "某老师", cover: "", duration: 3600, play: 1, favorites: 0,
                   pubdate: 0, typeid: typeid, episodes: 0, desc: "", tagtext: tags)
        }
        XCTAssertNil(Classify.reject(course("人教版七年级数学上册 全集"), grade: 7, subject: "数学", volume: "上册"))
        XCTAssertEqual(Classify.reject(course("人教版五年级数学上册"), grade: 7, subject: "数学", volume: "上册"), "年级不符")
        XCTAssertEqual(Classify.reject(course("七年级语文上册"), grade: 7, subject: "数学", volume: "上册"), "学科不符")
        XCTAssertEqual(Classify.reject(course("七年级数学下册"), grade: 7, subject: "数学", volume: "上册"), "册次不符")
        XCTAssertEqual(Classify.reject(course("七年级数学上册", typeid: 241), grade: 7, subject: "数学", volume: "上册"), "不在学习分区")
        XCTAssertEqual(Classify.reject(course("数学上册同步课", tags: "八年级"), grade: 7, subject: "数学", volume: "上册"), "年级不符")
    }

    func testSameCourseAndChapters() {
        func c(_ t: String, _ e: Int) -> Course {
            Course(bvid: "BV1", title: t, author: "", cover: "", duration: 0, play: 0, favorites: 0, pubdate: 0, typeid: 208, episodes: e, desc: "", tagtext: "")
        }
        XCTAssertTrue(Classify.sameCourse(c("全63集 <2026最新版三年级上册语文同步教材课文动画> 上下册63集", 63),
                                          c("【63集全【2025最新版三年级语文上册同步教材课文动画】三年级语文上下册63集", 0)))
        XCTAssertFalse(Classify.sameCourse(c("【25年秋】小学语文三年级语文上册 （部编版）全册 课文朗读", 0),
                                           c("全63集 <2026最新版三年级上册语文同步教材课文动画>", 63)))
        XCTAssertEqual(Chapters.label("1.2.1 有理数的概念"), "第一章")
        XCTAssertEqual(Chapters.label("2.1.1 有理数的加法（第1课时）"), "第二章")
        XCTAssertEqual(Chapters.label("第三单元 古诗"), "第三单元")
        XCTAssertNil(Chapters.label("正数和负数（第1课时）"))
    }

    func testAIJudgementCleanup() throws {
        let (id, j) = try XCTUnwrap(AIClient.judgement(["bvid": "BV1", "fit": "很对口", "kind": "同步课", "quality": 9]))
        XCTAssertEqual(id, "BV1")
        XCTAssertEqual(j.fit, "相关")
        XCTAssertEqual(j.level, "中等")
        XCTAssertEqual(j.quality, 5)
        XCTAssertEqual(try AIClient.parseJSONText("```json\n{\"a\":2}\n```")["a"] as? Int, 2)
        XCTAssertEqual(try AIClient.parseJSONText("结果如下：{\"a\":3} 以上")["a"] as? Int, 3)
    }
}
