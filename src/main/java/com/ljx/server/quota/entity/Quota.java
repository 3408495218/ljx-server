package com.ljx.server.quota.entity;

import com.ljx.server.quota.QuotaType;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "quota")
public class Quota {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long accountId;

    @Enumerated(EnumType.STRING)
    private QuotaType type;

    private int baseValue;

    private int addValue;

    private int limitValue;

    protected Quota() {
    }

    public Quota(Long accountId, QuotaType type, int baseValue) {
        this.accountId = accountId;
        this.type = type;
        this.baseValue = baseValue;
        this.addValue = 0;
        this.limitValue = Integer.MAX_VALUE;
    }

    /** 生效值 = base(等级) + add(购买)，封顶 limit(上限)；不受限类型 base 为 Integer.MAX_VALUE，故用 long 累加防溢出 */
    public int effective() {
        return (int) Math.min((long) baseValue + addValue, limitValue);
    }

    /** 升级时重写 base；add / limit 不变 */
    public void applyBase(int base) {
        this.baseValue = base;
    }

    public Long getAccountId() {
        return accountId;
    }

    public QuotaType getType() {
        return type;
    }

    public int getEffective() {
        return effective();
    }
}
