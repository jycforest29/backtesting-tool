package com.backtesting.persistence;

import jakarta.persistence.MappedSuperclass;
import org.hibernate.Hibernate;

import java.io.Serializable;
import java.util.Objects;

/**
 * 모든 JPA Entity 의 공통 base — equals/hashCode 를 안전하게 일관 정의한다.
 *
 * 왜 이게 필요한가:
 *  Lombok @Data 는 모든 필드에서 equals/hashCode 를 생성한다. JPA 환경에선 다음이 폭발한다.
 *   - @Lob 필드, 양방향 연관관계가 hashCode 호출 → LazyInitializationException, 무한 루프, 거대한 toString
 *   - 영속화 전(transient) → 영속화 후(managed) 사이 hashCode 가 바뀌면 HashSet/HashMap 에서 객체가 사라진다
 *
 * 이 base 의 계약 (Vlad Mihalcea 패턴):
 *  1) hashCode() 는 클래스 기반 — 영속화 전/후 동일 값 보장. HashSet 안전.
 *  2) equals() 는 (a) 같은 클래스(Hibernate proxy 언래핑) (b) id 가 둘 다 non-null 이고 동일.
 *     transient 두 객체는 reference equality (`this == o`) 만 통과 — 의도된 보수적 정의.
 *  3) final — 서브클래스가 망치는 것 차단.
 *
 * @param <ID> @Id 필드 타입
 */
@MappedSuperclass
public abstract class AbstractEntity<ID extends Serializable> {

    public abstract ID getId();

    @Override
    public final boolean equals(Object o) {
        if (this == o) return true;
        if (o == null) return false;
        Class<?> thisClass = Hibernate.getClass(this);
        Class<?> otherClass = Hibernate.getClass(o);
        if (!thisClass.equals(otherClass)) return false;
        ID id = getId();
        return id != null && Objects.equals(id, ((AbstractEntity<?>) o).getId());
    }

    @Override
    public final int hashCode() {
        return Hibernate.getClass(this).hashCode();
    }
}
