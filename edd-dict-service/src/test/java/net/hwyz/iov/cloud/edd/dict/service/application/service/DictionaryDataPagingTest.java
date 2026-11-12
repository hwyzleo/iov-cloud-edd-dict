package net.hwyz.iov.cloud.edd.dict.service.application.service;

import net.hwyz.iov.cloud.edd.dict.service.BaseTest;
import net.hwyz.iov.cloud.edd.dict.service.domain.model.Dictionary;
import net.hwyz.iov.cloud.framework.common.bean.PageResult;
import net.hwyz.iov.cloud.framework.web.util.PageUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 字典数据分页行为验证：动态表查询必须被 PageHelper 分页拦截，且不能先被字典定义查询消耗。
 */
@DisplayName("字典数据分页")
public class DictionaryDataPagingTest extends BaseTest {

    private static final String CATEGORY_CODE = "it_dict_paging";
    private static final String TABLE_NAME = "tb_" + CATEGORY_CODE;
    private static final String DICTIONARY_CODE = "it_paging_dict";
    private static final int TOTAL_ROWS = 25;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DictionaryAppService dictionaryAppService;

    @BeforeEach
    void setUpTable() {
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS `" + TABLE_NAME + "` ("
                + "`id` BIGINT NOT NULL AUTO_INCREMENT, "
                + "`name` VARCHAR(255) NOT NULL, "
                + "PRIMARY KEY (`id`)) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4");
        for (int i = 1; i <= TOTAL_ROWS; i++) {
            jdbcTemplate.update("INSERT INTO `" + TABLE_NAME + "` (`name`) VALUES (?)", "分页测试" + i);
        }
        jdbcTemplate.update("INSERT INTO tb_dictionary_category (name, code, type, enable, sort, row_version, row_valid,"
                + " create_time, modify_time) VALUES (?, ?, 'GLOBAL', 1, 99, 1, 1, NOW(), NOW())", "分页测试分类", CATEGORY_CODE);
        jdbcTemplate.update("INSERT INTO tb_dictionary (name, code, category_code, select_column, where_condition,"
                        + " enable, sort, row_version, row_valid, create_time, modify_time)"
                        + " VALUES (?, ?, ?, ?, '[]', 1, 99, 1, 1, NOW(), NOW())",
                "分页测试字典", DICTIONARY_CODE, CATEGORY_CODE, "[{\"targetColumn\":\"name\",\"aliasName\":\"\"}]");
    }

    @AfterEach
    void tearDownTable() {
        RequestContextHolder.resetRequestAttributes();
        jdbcTemplate.update("DELETE FROM tb_dictionary WHERE code = ?", DICTIONARY_CODE);
        jdbcTemplate.update("DELETE FROM tb_dictionary_category WHERE code = ?", CATEGORY_CODE);
        jdbcTemplate.execute("DROP TABLE IF EXISTS `" + TABLE_NAME + "`");
    }

    @Test
    @DisplayName("字典数据列表按 pageNum/pageSize 分页返回，total 为全量行数")
    void queryDataIsPaged() {
        Dictionary dictionary = dictionaryAppService.getForDataQuery(dictionaryId());

        PageResult<Map<String, Object>> firstPage = queryPage(dictionary, 1, 10);
        assertThat(firstPage.getTotal()).isEqualTo(TOTAL_ROWS);
        assertThat(firstPage.getItems()).hasSize(10);

        PageResult<Map<String, Object>> thirdPage = queryPage(dictionary, 3, 10);
        assertThat(thirdPage.getTotal()).isEqualTo(TOTAL_ROWS);
        assertThat(thirdPage.getItems()).hasSize(TOTAL_ROWS - 20);
    }

    @Test
    @DisplayName("分页在字典定义查询之后开启，不被元数据查询消耗")
    void pagingIsNotConsumedByDefinitionQuery() {
        bindPageRequest(2, 10);
        // 与 MptDictionaryController#getData 的执行顺序保持一致：先取字典定义，再开启分页
        Dictionary dictionary = dictionaryAppService.getForDataQuery(dictionaryId());
        PageUtil.startPage();

        List<Map<String, Object>> rows = dictionaryAppService.queryData(dictionary);
        PageResult<Map<String, Object>> page = PageUtil.getPageResult(rows);

        assertThat(page.getTotal()).isEqualTo(TOTAL_ROWS);
        assertThat(page.getItems()).hasSize(10);

        // 第二页内容必须与第一页不同，证明 offset 生效
        assertThat(page.getItems()).isNotEqualTo(queryPage(dictionary, 1, 10).getItems());
    }

    private PageResult<Map<String, Object>> queryPage(Dictionary dictionary, int pageNum, int pageSize) {
        bindPageRequest(pageNum, pageSize);
        PageUtil.startPage();
        return PageUtil.getPageResult(dictionaryAppService.queryData(dictionary));
    }

    private void bindPageRequest(int pageNum, int pageSize) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setParameter("pageNum", String.valueOf(pageNum));
        request.setParameter("pageSize", String.valueOf(pageSize));
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    private Long dictionaryId() {
        return jdbcTemplate.queryForObject("SELECT id FROM tb_dictionary WHERE code = ?", Long.class, DICTIONARY_CODE);
    }
}
