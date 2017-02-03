<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <title>Title</title>

    <link rel="stylesheet" href="https://maxcdn.bootstrapcdn.com/bootstrap/3.3.7/css/bootstrap.min.css">
    <link href="${ publicAt('css/main.css')}" rel="stylesheet">
</head>
<body>

<section id="app">
    <section class="search-form">
        <div>
            <input type="search" placeholder="What are you looking for?" v-model="query">
            <button @click="search()" v-if="!loading">Search</button>
            <button v-if="loading">Searching...</button>
        </div>
        <div class="alert alert-danger" role="alert" v-if="error">
            <span class="glyphicon glyphicon-exclamation-sign" aria-hidden="true"></span>
            {{ error }}
        </div>

        <ul id="results_">
            <li v-for="result in results"><a v-bind:href="result">{{ result }}</a></li>
        </ul>

    </section>

</section>
<script src="https://unpkg.com/vue@2.1.10/dist/vue.js"></script>
<script src="https://unpkg.com/vue-resource@1.1.0/dist/vue-resource.js"></script>
<script src="${ publicAt('js/app.js')}"></script>
</body>
</html>