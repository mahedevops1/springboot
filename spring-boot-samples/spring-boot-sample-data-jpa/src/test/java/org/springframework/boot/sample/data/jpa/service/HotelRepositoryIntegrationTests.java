/*
 * Copyright 2013 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springframework.boot.sample.data.jpa.service;

import static org.hamcrest.Matchers.*;
import static org.junit.Assert.*;

import java.util.List;

import org.junit.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.sample.data.jpa.AbstractIntegrationTests;
import org.springframework.boot.sample.data.jpa.domain.City;
import org.springframework.boot.sample.data.jpa.domain.Hotel;
import org.springframework.boot.sample.data.jpa.domain.HotelSummary;
import org.springframework.boot.sample.data.jpa.domain.Rating;
import org.springframework.boot.sample.data.jpa.domain.RatingCount;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort.Direction;

/**
 * Integration tests for {@link HotelRepository}.
 *
 * @author Oliver Gierke
 */
public class HotelRepositoryIntegrationTests extends AbstractIntegrationTests {

	@Autowired CityRepository cityRepository;
	@Autowired HotelRepository repository;
	
	@Test
	public void executesQueryMethodsCorrectly() {
		
		City city = cityRepository.findAll(new PageRequest(0, 1, Direction.ASC, "name")).getContent().get(0);
		assertThat(city.getName(), is("Atlanta"));
		
		Page<HotelSummary> hotels = repository.findByCity(city, new PageRequest(0, 10, Direction.ASC, "name"));
		
		Hotel hotel = repository.findByCityAndName(city, hotels.getContent().get(0).getName());
		assertThat(hotel.getName(), is("Doubletree"));
		
		List<RatingCount> counts = repository.findRatingCounts(hotel);
		
		assertThat(counts, hasSize(1));
		assertThat(counts.get(0).getRating(), is(Rating.AVERAGE));
		assertThat(counts.get(0).getCount(), is(2L));
	}
}
