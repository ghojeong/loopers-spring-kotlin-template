package com.loopers.domain.product

import com.loopers.domain.brand.Brand
import com.loopers.support.error.CoreException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class ProductTest {
    @Test
    fun `상품을 생성할 수 있다`() {
        // given
        val name = "운동화"
        val price = Price(amount = BigDecimal("100000"), currency = Currency.KRW)
        val brand = Brand(name = "나이키", description = "스포츠 브랜드")

        // when
        val product = Product(name = name, price = price, brand = brand)

        // then
        assertThat(product.name).isEqualTo(name)
        assertThat(product.price).isEqualTo(price)
        assertThat(product.brand).isEqualTo(brand)
    }

    @Test
    fun `상품명이 비어있으면 예외가 발생한다`() {
        // given
        val price = Price(amount = BigDecimal("100000"), currency = Currency.KRW)
        val brand = Brand(name = "나이키", description = "스포츠 브랜드")

        // when & then
        assertThatThrownBy {
            Product(name = "", price = price, brand = brand)
        }.isInstanceOf(CoreException::class.java)
            .hasMessageContaining("상품명")
    }
}
