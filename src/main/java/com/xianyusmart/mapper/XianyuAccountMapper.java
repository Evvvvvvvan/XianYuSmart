package com.xianyusmart.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xianyusmart.entity.XianyuAccount;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 闲鱼账号Mapper
 */
@Mapper
public interface XianyuAccountMapper extends BaseMapper<XianyuAccount> {

    @Select("SELECT account.* FROM xianyu_account account WHERE account.status = 1 " +
            "AND EXISTS (SELECT 1 FROM xianyu_cookie cookie " +
            "WHERE cookie.xianyu_account_id = account.id AND cookie.cookie_status = 1 " +
            "AND cookie.cookie_text IS NOT NULL AND cookie.cookie_text <> '')")
    List<XianyuAccount> selectReconnectableAccounts();

    @Update("UPDATE xianyu_account SET websocket_sync_pts = #{pts}, websocket_sync_seq = #{seq}, " +
            "websocket_sync_timestamp = #{timestamp} WHERE id = #{accountId} " +
            "AND (websocket_sync_pts IS NULL OR websocket_sync_pts < #{pts})")
    int advanceWebSocketSyncCursor(@Param("accountId") Long accountId,
                                   @Param("pts") long pts,
                                   @Param("seq") long seq,
                                   @Param("timestamp") long timestamp);
}
